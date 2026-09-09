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

public class JoinVerificationService {
    private static final Set<String> DECIDED = ConcurrentHashMap.newKeySet();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final PluginLogger LOG = PluginLogger.of(JoinVerificationService.class);
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
        String nativeKind = nativeEventKind(event);
        if (isRobotMembershipEvent(nativeKind)) {
            LOG.info(PluginLogger.JOIN, "忽略机器人入群/退群事件，不是成员入群申请"
                    + context(event) + ", nativeType=" + nativeKind);
            return;
        }
        AutomationPolicy policy;
        try {
            policy = policies.resolve(event.connectionId(), event.channelId());
        } catch (RuntimeException error) {
            LOG.warn(PluginLogger.JOIN, "读取群策略失败，无法审核" + context(event), error);
            return;
        }
        String requestId = joinRequestId(event);
        String comment = joinComment(event);
        LOG.info(PluginLogger.JOIN, "收到入群申请"
                + context(event)
                + ", requestId=" + blankToDash(requestId)
                + ", nativeType=" + blankToDash(nativeKind)
                + ", official=" + moderation.isOfficial(event.connectionId())
                + ", enabled=" + policy.enabled()
                + ", joinVerificationEnabled=" + policy.joinVerificationEnabled()
                + ", aiFallback=" + policy.aiFallbackEnabled()
                + ", failClosed=" + policy.failClosed()
                + ", approvedAnswers=" + policy.approvedAnswers().size()
                + ", comment=" + preview(comment));
        if (!policy.enabled() || !policy.joinVerificationEnabled()) {
            LOG.info(PluginLogger.JOIN, "策略未开启入群验证，跳过" + context(event)
                    + ", enabled=" + policy.enabled()
                    + ", joinVerificationEnabled=" + policy.joinVerificationEnabled());
            return;
        }
        if (requestId.isBlank()) {
            LOG.warn(PluginLogger.JOIN, "申请没有 join_request_id / notification_seq，无法审批"
                    + context(event) + ", nativeType=" + blankToDash(nativeKind));
            return;
        }
        String key = event.connectionId() + ":" + requestId;
        if (DECIDED.contains(key)) {
            LOG.info(PluginLogger.JOIN, "该申请已处理过，跳过" + context(event) + ", requestId=" + requestId);
            return;
        }
        if (!IN_FLIGHT.add(key)) {
            LOG.info(PluginLogger.JOIN, "该申请正在处理，跳过重复事件" + context(event) + ", requestId=" + requestId);
            return;
        }
        try {
            Decision decision = ruleDecision(comment, policy);
            LOG.info(PluginLogger.JOIN, "规则匹配结果=" + decision
                    + context(event)
                    + ", requestId=" + requestId
                    + ", comment=" + preview(comment));
            if (decision == Decision.UNDECIDED && policy.aiFallbackEnabled()) {
                LOG.info(PluginLogger.JOIN, "规则未命中，请求 AI 兜底" + context(event) + ", requestId=" + requestId);
                framework.ai().chat(new PluginAiChatRequest("只输出 ALLOW 或 REJECT。根据入群验证文本判断是否可通过，无法确认时输出 REJECT。", comment,
                        blank(policy.providerCode()), blank(policy.modelCode()), List.of(),
                        new PluginAiExecutionContext(null, event.userId(), event.connectionId(), event.channelId(), event.messageId(), "GROUP_JOIN_VERIFICATION", requestId, List.of(), List.of()), false))
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                LOG.warn(PluginLogger.JOIN, "AI 兜底失败" + context(event) + ", requestId=" + requestId, error);
                            }
                            String reply = error == null && result != null ? result.content() : "";
                            Decision aiDecision = error == null && "ALLOW".equalsIgnoreCase(reply == null ? "" : reply.trim())
                                    ? Decision.APPROVE
                                    : policy.failClosed() ? Decision.REJECT : Decision.UNDECIDED;
                            LOG.info(PluginLogger.JOIN, "AI 兜底结果=" + aiDecision
                                    + context(event)
                                    + ", requestId=" + requestId
                                    + ", reply=" + preview(reply));
                            decide(event, key, requestId, comment, aiDecision);
                        });
                return;
            }
            Decision finalDecision = decision == Decision.UNDECIDED && policy.failClosed() ? Decision.REJECT : decision;
            if (decision == Decision.UNDECIDED && policy.failClosed()) {
                LOG.info(PluginLogger.JOIN, "规则未命中且未开 AI 兜底，按失败关闭拒绝" + context(event) + ", requestId=" + requestId);
            }
            decide(event, key, requestId, comment, finalDecision);
        } catch (RuntimeException error) {
            IN_FLIGHT.remove(key);
            LOG.error(PluginLogger.JOIN, "入群审核处理异常" + context(event) + ", requestId=" + requestId, error);
        }
    }

    private void decide(PluginEvent event, String key, String requestId, String comment, Decision decision) {
        if (decision == Decision.UNDECIDED) {
            IN_FLIGHT.remove(key);
            LOG.info(PluginLogger.JOIN, "未做出通过/拒绝（规则未命中且未失败关闭），留给群主手动处理"
                    + context(event) + ", requestId=" + requestId + ", comment=" + preview(comment));
            return;
        }
        boolean approve = decision == Decision.APPROVE;
        boolean official = moderation.isOfficial(event.connectionId());
        LOG.info(PluginLogger.JOIN, "准备发送审批: decision=" + decision
                + ", protocol=" + (official ? "official" : "milky")
                + context(event)
                + ", requestId=" + requestId
                + ", comment=" + preview(comment));
        var action = official
                ? moderation.approveJoin(event.connectionId(), event.channelId(), event.userId(), approve, requestId)
                : framework.messagingRaw().invoke(event.connectionId(), approve ? "accept_group_request" : "reject_group_request",
                        groupRequestPayload(event, requestId));
        action.whenComplete((ignored, error) -> {
            IN_FLIGHT.remove(key);
            if (error != null) {
                LOG.error(PluginLogger.JOIN, "审批接口调用失败: decision=" + decision
                        + context(event) + ", requestId=" + requestId, error);
                return;
            }
            DECIDED.add(key);
            LOG.info(PluginLogger.JOIN, "审批已发送: decision=" + decision
                    + context(event) + ", requestId=" + requestId);
            framework.documents("qqbot-automation").save("join-verification-audit", key, Map.of(
                    "connectionId", event.connectionId(), "channelId", event.channelId(), "userId", event.userId(),
                    "requestId", requestId, "decision", approve ? "APPROVE" : "REJECT",
                    "comment", comment == null ? "" : comment, "createdAt", System.currentTimeMillis()));
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
        String comment = value(event.referrer() == null ? null : event.referrer().get("comment"));
        if (!comment.isBlank()) {
            return comment;
        }
        comment = nativeText(event, "comment", "verify_message");
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

    private String nativeEventKind(PluginEvent event) {
        Object nativeType = nativeValue(event, "native_type");
        if (nativeType != null && !String.valueOf(nativeType).isBlank()) {
            return String.valueOf(nativeType).trim();
        }
        return event.nativeType() == null ? "" : event.nativeType().trim();
    }

    private boolean isRobotMembershipEvent(String nativeKind) {
        return "GROUP_ADD_ROBOT".equalsIgnoreCase(nativeKind) || "GROUP_DEL_ROBOT".equalsIgnoreCase(nativeKind);
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
        List<String> candidates = matchCandidates(comment);
        if (matchesAny(candidates, policy.rejectedAnswers())) {
            return Decision.REJECT;
        }
        if (matchesAny(candidates, policy.approvedAnswers())) {
            return Decision.APPROVE;
        }
        return Decision.UNDECIDED;
    }

    private boolean matchesAny(List<String> candidates, List<String> answers) {
        for (String answer : answers) {
            String needle = normalize(answer);
            if (needle.isEmpty()) {
                continue;
            }
            for (String candidate : candidates) {
                if (candidate.isEmpty()) {
                    continue;
                }
                if (candidate.contains(needle) || needle.contains(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> matchCandidates(String comment) {
        String normalized = normalize(comment);
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add(normalized);
        if (comment == null || comment.isBlank()) {
            return candidates;
        }
        for (String line : comment.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            candidates.add(normalize(trimmed));
            int colon = Math.max(trimmed.lastIndexOf('：'), trimmed.lastIndexOf(':'));
            if (colon >= 0 && colon < trimmed.length() - 1) {
                candidates.add(normalize(trimmed.substring(colon + 1)));
            }
        }
        return candidates;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "(空)";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 80 ? compact : compact.substring(0, 80) + "…";
    }

    private String context(PluginEvent event) {
        return ": connection=" + event.connectionId()
                + ", group=" + event.channelId()
                + ", user=" + event.userId();
    }

    private enum Decision { APPROVE, REJECT, UNDECIDED }
}
