package online.yudream.plugin.qqbotautomation.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 群聊风险监测：每个群按策略累计消息，满 N 条整批送 AI 审核；送检内容带完整用户上下文
 * （QQ/openid、已绑定账号昵称、时间），AI 逐条给出风险判定与置信度，按置信度自动禁言
     * （Milky set_group_member_mute / 官方 restrict_chat_setting）并向指定告警群/管理员推送告警。
 */
public class RiskMonitorService {
    private static final String LOG_COLLECTION = "risk-check-log";
    private static final int MAX_BATCH_SIZE = 100;
    private static final int CONTENT_PREVIEW_LENGTH = 100;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String SYSTEM_PROMPT = """
            你是 QQ 群聊内容安全审核员。逐条判断用户消息是否存在风险（广告引流、诈骗、辱骂攻击、色情、政治敏感、违法违规、刷屏等）。
            只输出 JSON 数组，不要输出任何其他文字。每条有风险的消息一个元素：
            [{"index":消息序号,"confidence":0-100的置信度,"category":"风险类别","reason":"一句话判定理由"}]
            没有风险消息时输出 []。拿不准的消息不要上报；只有明确可疑时才给出 60 以上的置信度。""";
    private static final Logger LOGGER = Logger.getLogger(RiskMonitorService.class.getName());

    private final AutomationPolicyService policies;
    private final PluginDocumentStore documents;
    private final FrameworkServices framework;
    private final GroupModerationService moderation;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, GroupBuffer> buffers = new ConcurrentHashMap<>();

    public RiskMonitorService(AutomationPolicyService policies, PluginDocumentStore documents,
                              FrameworkServices framework, GroupModerationService moderation) {
        this.policies = policies;
        this.documents = documents;
        this.framework = framework;
        this.moderation = moderation;
    }

    public void handle(PluginEvent event) {
        String content = event.content() == null ? "" : event.content().trim();
        if (content.isEmpty() || content.startsWith("/") || event.userId() == null || event.userId().isBlank()) {
            return;
        }
        if (event.userId().equals(event.selfId())) {
            return;
        }
        AutomationPolicy policy;
        try {
            policy = policies.resolve(event.connectionId(), event.channelId());
        } catch (RuntimeException e) {
            return;
        }
        if (!policy.enabled() || !policy.riskMonitorEnabled()) {
            return;
        }
        String key = event.connectionId() + ":" + event.channelId();
        BufferedMessage message = new BufferedMessage(event.userId(), nickname(event.userId()), content,
                event.messageId(), System.currentTimeMillis());
        List<BufferedMessage> batch = null;
        synchronized (buffers) {
            GroupBuffer buffer = buffers.computeIfAbsent(key,
                    ignored -> new GroupBuffer(event.platform(), event.selfId()));
            buffer.platform = event.platform();
            buffer.selfId = event.selfId();
            buffer.messages.add(message);
            if (buffer.messages.size() >= Math.clamp(policy.riskBatchSize(), 5, MAX_BATCH_SIZE)) {
                batch = List.copyOf(buffer.messages);
                buffer.messages.clear();
            }
        }
        if (batch != null) {
            inspect(event.connectionId(), event.channelId(), policy, batch,
                    buffers.get(key).platform, buffers.get(key).selfId);
        }
    }

    private void inspect(String connectionId, String channelId, AutomationPolicy policy, List<BufferedMessage> batch,
                         String platform, String selfId) {
        StringBuilder prompt = new StringBuilder("以下是按时间顺序排列的群聊消息，格式为【序号】昵称(QQ)：内容\n");
        for (int i = 0; i < batch.size(); i++) {
            BufferedMessage message = batch.get(i);
            prompt.append('【').append(i + 1).append('】')
                    .append(message.nickname()).append('(').append(message.userId()).append(") ")
                    .append(TIME_FORMAT.format(Instant.ofEpochMilli(message.timestamp()))).append('：')
                    .append(message.content()).append('\n');
        }
        framework.ai().chat(new PluginAiChatRequest(SYSTEM_PROMPT, prompt.toString(),
                        blank(policy.providerCode()), blank(policy.modelCode()), List.of(),
                        new PluginAiExecutionContext(null, null, connectionId, channelId, null,
                                "RISK_MONITOR", UUID.randomUUID().toString(), List.of(), List.of()), false))
                .whenComplete((result, error) -> {
                    if (error != null || result == null) {
                        LOGGER.log(Level.WARNING, "[YuDreamAdmin] [QQ 群自动化] risk inspection failed: connection=" + connectionId + ", channel=" + channelId, error);
                        return;
                    }
                    List<RiskFinding> findings = parseFindings(result.content(), batch);
                    if (!findings.isEmpty()) {
                        enforce(connectionId, channelId, policy, findings, platform, selfId);
                    }
                    audit(connectionId, channelId, batch.size(), findings);
                });
    }

    private void enforce(String connectionId, String channelId, AutomationPolicy policy, List<RiskFinding> findings,
                         String platform, String selfId) {
        for (RiskFinding finding : findings) {
            long muteSeconds = policy.riskMuteEnabled() ? policy.muteSecondsForConfidence(finding.confidence()) : 0;
            String disposition = "仅告警";
            if (muteSeconds > 0) {
                try {
                    moderation.mute(connectionId, channelId, finding.message().userId(), muteSeconds)
                            .whenComplete((ignored, error) -> {
                                if (error != null) {
                                    LOGGER.log(Level.WARNING, "[YuDreamAdmin] [QQ 群自动化] risk mute failed: connection=" + connectionId
                                            + ", channel=" + channelId + ", user=" + finding.message().userId(), error);
                                }
                            });
                    disposition = "已禁言 " + durationText(muteSeconds);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "[YuDreamAdmin] [QQ 群自动化] risk mute rejected: connection=" + connectionId
                            + ", channel=" + channelId + ", user=" + finding.message().userId(), e);
                    disposition = "禁言失败：" + e.getMessage();
                }
            }
            if (finding.confidence() >= policy.riskAlertConfidence()) {
                pushAlerts(connectionId, channelId, policy, finding, disposition, platform, selfId);
            }
        }
    }

    private void pushAlerts(String connectionId, String channelId, AutomationPolicy policy, RiskFinding finding,
                            String disposition, String platform, String selfId) {
        String text = "⚠️ 群聊风险提醒\n来源群：" + channelId
                + "\n发送者：" + finding.message().nickname() + "（" + finding.message().userId() + "）"
                + "\n类型：" + finding.category() + "（置信度 " + finding.confidence() + "%）"
                + "\n内容：" + preview(finding.message().content())
                + "\n判定理由：" + finding.reason()
                + "\n处置：" + disposition;
        if (policy.riskAlertGroup()) {
            String alertChannelId = policy.riskAlertGroupChannelId() == null ? "" : policy.riskAlertGroupChannelId().trim();
            if (alertChannelId.isBlank()) {
                LOGGER.warning("[YuDreamAdmin] [QQ 群自动化] risk group alert skipped: alert group not configured, source=" + channelId);
            } else {
                try {
                    framework.messaging().send(new PluginMessageRequest(connectionId, platform, selfId, alertChannelId,
                            new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, Map.of())));
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "[YuDreamAdmin] [QQ 群自动化] risk group alert failed: channel=" + alertChannelId, e);
                }
            }
        }
        if (policy.riskAlertAdmin()) {
            for (String adminUserId : policy.riskAlertAdminUserIds()) {
                try {
                    framework.messaging().sendDirectToBoundUser(adminUserId,
                            new PluginMessageContent(PluginMessageContent.Type.TEXT,
                                    "【群 " + channelId + "】" + text, null, Map.of()));
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "[YuDreamAdmin] [QQ 群自动化] risk admin alert failed: userId=" + adminUserId, e);
                }
            }
        }
    }

    private List<RiskFinding> parseFindings(String content, List<BufferedMessage> batch) {
        List<RiskFinding> findings = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return findings;
        }
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return findings;
        }
        try {
            JsonNode array = json.readTree(content.substring(start, end + 1));
            if (!array.isArray()) {
                return findings;
            }
            for (JsonNode node : array) {
                int index = node.path("index").asInt(0);
                int confidence = Math.clamp(node.path("confidence").asInt(0), 0, 100);
                if (index < 1 || index > batch.size() || confidence <= 0) {
                    continue;
                }
                findings.add(new RiskFinding(batch.get(index - 1), confidence,
                        text(node.path("category").asText(), "未分类"), text(node.path("reason").asText(), "未给出理由")));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [QQ 群自动化] risk inspection response parse failed", e);
        }
        return findings;
    }

    private void audit(String connectionId, String channelId, int batchSize, List<RiskFinding> findings) {
        try {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("id", UUID.randomUUID().toString());
            document.put("connectionId", connectionId);
            document.put("channelId", channelId);
            document.put("batchSize", batchSize);
            document.put("riskyCount", findings.size());
            document.put("createdAt", System.currentTimeMillis());
            if (!findings.isEmpty()) {
                document.put("findings", findings.stream().map(finding -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("userId", finding.message().userId());
                    item.put("nickname", finding.message().nickname());
                    item.put("messageId", finding.message().messageId() == null ? "" : finding.message().messageId());
                    item.put("content", preview(finding.message().content()));
                    item.put("category", finding.category());
                    item.put("confidence", finding.confidence());
                    item.put("reason", finding.reason());
                    return item;
                }).toList());
            }
            documents.save(LOG_COLLECTION, String.valueOf(document.get("id")), document);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [QQ 群自动化] risk audit save failed", e);
        }
    }

    /** 已绑定平台账号时展示昵称，否则只能给出 QQ 号/openid（官方协议拿不到未绑定用户的昵称）。 */
    private String nickname(String qq) {
        try {
            return framework.users().findByQq(qq)
                    .map(profile -> text(profile.nickname(), null) != null ? profile.nickname() : text(profile.username(), null))
                    .filter(name -> name != null && !name.isBlank())
                    .orElse("QQ " + qq);
        } catch (RuntimeException e) {
            return "QQ " + qq;
        }
    }

    private String preview(String content) {
        String flattened = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return flattened.length() <= CONTENT_PREVIEW_LENGTH ? flattened : flattened.substring(0, CONTENT_PREVIEW_LENGTH) + "…";
    }

    private String durationText(long seconds) {
        if (seconds % 86400 == 0) {
            return (seconds / 86400) + " 天";
        }
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + " 小时";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + " 分钟";
        }
        return seconds + " 秒";
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record BufferedMessage(String userId, String nickname, String content, String messageId, long timestamp) { }

    private record RiskFinding(BufferedMessage message, int confidence, String category, String reason) { }

    private static final class GroupBuffer {
        private final List<BufferedMessage> messages = new ArrayList<>();
        private String platform;
        private String selfId;

        private GroupBuffer(String platform, String selfId) {
            this.platform = platform;
            this.selfId = selfId;
        }
    }
}
