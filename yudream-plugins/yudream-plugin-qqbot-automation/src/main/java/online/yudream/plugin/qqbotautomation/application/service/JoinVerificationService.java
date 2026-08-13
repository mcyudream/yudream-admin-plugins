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

    public JoinVerificationService(AutomationPolicyService policies, FrameworkServices framework) { this.policies = policies; this.framework = framework; }

    public void handle(PluginEvent event) {
        AutomationPolicy policy = policies.get(event.connectionId(), event.channelId());
        if (!policy.enabled() || !policy.joinVerificationEnabled()) return;
        String requestId = value(event.referrer().get("requestId"));
        if (requestId.isBlank() || !DECIDED.add(event.connectionId() + ":" + requestId)) return;
        String comment = event.content() == null ? "" : event.content();
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
        String requestId = value(event.referrer().get("requestId"));
        String decisionKey = event.connectionId() + ":" + requestId;
        framework.messagingRaw().invoke(event.connectionId(), approve ? "accept_group_request" : "reject_group_request",
                groupRequestPayload(event, requestId)).whenComplete((ignored, error) -> {
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
        payload.putIfAbsent("notification_seq", Long.parseLong(requestId));
        payload.putIfAbsent("notification_type", "group_invited_join_request".equals(event.nativeType())
                ? "invited_join_request" : "join_request");
        return payload;
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
