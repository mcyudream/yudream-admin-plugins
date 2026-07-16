package online.yudream.plugin.qqbotautomation.application.dto;

import java.util.List;

/**
 * A group-level policy override. A {@code null} field inherits the connection default.
 */
public record AutomationPolicyOverride(String connectionId, String channelId, Boolean enabled, Boolean mediaEnabled,
                                       String mediaProviderEndpoint, Boolean joinVerificationEnabled,
                                       List<String> approvedAnswers, List<String> rejectedAnswers,
                                       Boolean aiFallbackEnabled, Boolean failClosed, String providerCode,
                                       String modelCode) {
    public AutomationPolicyOverride(String connectionId, String channelId, Boolean enabled, Boolean mediaEnabled,
                                    String mediaProviderEndpoint, Boolean joinVerificationEnabled,
                                    List<String> approvedAnswers, List<String> rejectedAnswers,
                                    Boolean aiFallbackEnabled, Boolean failClosed, String providerCode) {
        this(connectionId, channelId, enabled, mediaEnabled, mediaProviderEndpoint, joinVerificationEnabled,
                approvedAnswers, rejectedAnswers, aiFallbackEnabled, failClosed, providerCode, null);
    }

    public static AutomationPolicyOverride complete(AutomationPolicy policy) {
        return new AutomationPolicyOverride(policy.connectionId(), policy.channelId(), policy.enabled(),
                policy.mediaEnabled(), policy.mediaProviderEndpoint(), policy.joinVerificationEnabled(),
                policy.approvedAnswers(), policy.rejectedAnswers(), policy.aiFallbackEnabled(), policy.failClosed(),
                policy.providerCode(), policy.modelCode());
    }
}
