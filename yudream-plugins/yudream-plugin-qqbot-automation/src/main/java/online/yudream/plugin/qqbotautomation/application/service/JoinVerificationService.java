package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.plugin.qqbotautomation.application.dto.AutomationPolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JoinVerificationService {
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
                    .whenComplete((result, error) -> decide(event, error == null && result != null && "ALLOW".equalsIgnoreCase(result.content().trim()) ? Decision.APPROVE : policy.failClosed() ? Decision.REJECT : Decision.UNDECIDED));
            return;
        }
        decide(event, decision == Decision.UNDECIDED && policy.failClosed() ? Decision.REJECT : decision);
    }

    private void decide(PluginEvent event, Decision decision) {
        if (decision == Decision.UNDECIDED) return;
        boolean approve = decision == Decision.APPROVE;
        framework.messagingRaw().invoke(event.connectionId(), "set_group_add_request", Map.of(
                "flag", event.referrer().get("requestId"), "sub_type", "add", "approve", approve,
                "reason", approve ? "验证通过" : "验证未通过"));
        framework.documents("qqbot-automation").save("join-verification-audit", event.connectionId() + ":" + event.referrer().get("requestId"), Map.of(
                "connectionId", event.connectionId(), "channelId", event.channelId(), "userId", event.userId(),
                "requestId", event.referrer().get("requestId"), "decision", approve ? "APPROVE" : "REJECT", "createdAt", System.currentTimeMillis()));
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
