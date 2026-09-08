package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JoinVerificationService {
    private static final Logger LOGGER = Logger.getLogger(JoinVerificationService.class.getName());
    private static final Set<String> DECIDED = ConcurrentHashMap.newKeySet();
    private final AutomationPolicyService policies;
    private final FrameworkServices framework;
    private final GroupModerationService moderation;

    public JoinVerificationService(AutomationPolicyService policies, FrameworkServices framework, GroupModerationService moderation) {
        this.policies = policies;
        this.framework = framework;
        this.moderation = moderation;
    }

    /** 兼容旧调用方：未提供群管理原语时按 Milky 行为运行（官方判定视为非官方）。 */
    public JoinVerificationService(AutomationPolicyService policies, FrameworkServices framework) {
        this(policies, framework, new GroupModerationService(framework));
    }

    public void handle(PluginEvent event) {
        AutomationPolicy policy = policies.resolve(event.connectionId(), event.channelId());
        if (!policy.enabled() || !policy.joinVerificationEnabled()) {
            LOGGER.fine("[YuDreamAdmin] [QQ 群自动化] skip join verification: connection=" + event.connectionId()
                    + ", channel=" + event.channelId() + ", enabled=" + policy.enabled()
                    + ", joinVerificationEnabled=" + policy.joinVerificationEnabled());
            return;
        }
        String requestId = joinRequestId(event);
        if (requestId.isBlank() || !DECIDED.add(event.connectionId() + ":" + requestId)) {
            if (requestId.isBlank()) {
                LOGGER.warning("[YuDreamAdmin] [QQ 群自动化] skip join verification without request id: connection="
                        + event.connectionId() + ", channel=" + event.channelId() + ", user=" + event.userId());
            }
            return;
        }
        String comment = joinComment(event);
        Decision decision = ruleDecision(comment, policy);
        if (decision == Decision.UNDECIDED && policy.aiFallbackEnabled()) {
            framework.ai().chat(new PluginAiChatRequest("只输出 ALLOW 或 REJECT。根据入群验证文本判断是否可通过，无法确认时输出 REJECT。", comment,
                    blank(policy.providerCode()), blank(policy.modelCode()), List.of(),
                    new PluginAiExecutionContext(null, event.userId(), event.connectionId(), event.channelId(), event.messageId(), "GROUP_JOIN_VERIFICATION", requestId, List.of(), List.of()), false))
                    .whenComplete((result, error) -> {
                        if (error != null) LOGGER.log(Level.WARNING, "[YuDreamAdmin] [QQ 群自动化] group join verification AI fallback failed: connection=" + event.connectionId() + ", channel=" + event.channelId(), error);
                        decide(event, error == null && result != null && "ALLOW".equalsIgnoreCase(result.content().trim()) ? Decision.APPROVE : policy.failClosed() ? Decision.REJECT : Decision.UNDECIDED);
                    });
            return;
        }
        decide(event, decision == Decision.UNDECIDED && policy.failClosed() ? Decision.REJECT : decision);
    }

    private void decide(PluginEvent event, Decision decision) {
        if (decision == Decision.UNDECIDED) return;
        boolean approve = decision == Decision.APPROVE;
        String requestId = joinRequestId(event);
        String decisionKey = event.connectionId() + ":" + requestId;
        // 官方机器人走适配器的审批接口（group_openid + member_openid + join_request_id）；Milky 沿用通知序号审批
        var action = moderation.isOfficial(event.connectionId())
                ? moderation.approveJoin(event.connectionId(), event.channelId(), event.userId(), approve, requestId)
                : framework.messagingRaw().invoke(event.connectionId(), approve ? "accept_group_request" : "reject_group_request",
                        groupRequestPayload(event, requestId));
        action.whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.log(Level.SEVERE, "[YuDreamAdmin] [QQ 群自动化] group request decision send failed: connection=" + event.connectionId() + ", channel=" + event.channelId() + ", decision=" + decision, error);
                DECIDED.remove(decisionKey);
                return;
            }
            framework.documents("qqbot-automation").save("join-verification-audit", decisionKey, Map.of(
                    "connectionId", event.connectionId(), "channelId", event.channelId(), "userId", event.userId(),
                    "requestId", requestId, "decision", approve ? "APPROVE" : "REJECT", "createdAt", System.currentTimeMillis()));
        });
    }

    private Map<String, Object> groupRequestPayload(PluginEvent event, String requestId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (event.nativeData() instanceof Map<?, ?> nativeData) {
            nativeData.forEach((key, value) -> payload.put(String.valueOf(key), value));
        }
        // Milky 的 notification_seq 是数值；仅 Milky 分支会走到这里
        payload.putIfAbsent("notification_seq", Long.parseLong(requestId));
        payload.putIfAbsent("notification_type", "group_invited_join_request".equals(event.nativeType())
                ? "invited_join_request" : "join_request");
        return payload;
    }

    private String joinRequestId(PluginEvent event) {
        String requestId = value(event.referrer() == null ? null : event.referrer().get("requestId"));
        if (!requestId.isBlank()) {
            return requestId;
        }
        requestId = nativeText(event, "join_request_id", "request_id", "notification_seq", "id");
        if (!requestId.isBlank()) {
            return requestId;
        }
        return event.messageId() == null ? "" : event.messageId().trim();
    }

    private String joinComment(PluginEvent event) {
        if (event.content() != null && !event.content().isBlank()) {
            return event.content();
        }
        String comment = nativeText(event, "comment", "verify_message");
        if (!comment.isBlank()) {
            return comment;
        }
        Object verifyInfo = nativeValue(event, "verify_info");
        if (verifyInfo instanceof Map<?, ?> info) {
            Object message = info.get("verify_message");
            if (message != null && !String.valueOf(message).isBlank()) {
                return String.valueOf(message).trim();
            }
            Object list = info.get("review_qa_list");
            if (list instanceof List<?> qaList) {
                StringBuilder builder = new StringBuilder();
                for (Object item : qaList) {
                    if (!(item instanceof Map<?, ?> qa)) {
                        continue;
                    }
                    String question = value(qa.get("question")).trim();
                    String answer = value(qa.get("answer")).trim();
                    if (question.isEmpty() && answer.isEmpty()) {
                        continue;
                    }
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    if (!question.isEmpty()) {
                        builder.append(question).append('：');
                    }
                    builder.append(answer);
                }
                if (!builder.isEmpty()) {
                    return builder.toString();
                }
            }
        }
        return "";
    }

    private String nativeText(PluginEvent event, String... keys) {
        for (String key : keys) {
            Object value = nativeValue(event, key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private Object nativeValue(PluginEvent event, String key) {
        if (!(event.nativeData() instanceof Map<?, ?> nativeData)) {
            return null;
        }
        Object value = nativeData.get(key);
        if (value != null) {
            return value;
        }
        Object nested = nativeData.get("native");
        return nested instanceof Map<?, ?> nestedNative ? nestedNative.get(key) : null;
    }

    private Decision ruleDecision(String comment, AutomationPolicy policy) {
        String normalized = normalize(comment);
        if (policy.rejectedAnswers().stream().map(this::normalize).anyMatch(normalized::contains)) return Decision.REJECT;
        if (policy.approvedAnswers().stream().map(this::normalize).anyMatch(normalized::contains)) return Decision.APPROVE;
        return Decision.UNDECIDED;
    }
    private String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT); }
    private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String value(Object value) { return value == null ? "" : String.valueOf(value); }
    private enum Decision { APPROVE, REJECT, UNDECIDED }
}
