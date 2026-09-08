package online.yudream.plugin.qqbotautomation.application.dto;

import java.util.List;

/**
 * A group-level policy override. A {@code null} field inherits the connection default.
 */
public record AutomationPolicyOverride(String connectionId, String channelId, Boolean enabled, Boolean mediaEnabled,
                                       String mediaProviderEndpoint, Boolean joinVerificationEnabled,
                                       List<String> approvedAnswers, List<String> rejectedAnswers,
                                       Boolean aiFallbackEnabled, Boolean failClosed, String providerCode,
                                       String modelCode,
                                       Boolean riskMonitorEnabled, Integer riskBatchSize,
                                       Integer riskAlertConfidence, Boolean riskAlertGroup, Boolean riskAlertAdmin,
                                       List<String> riskAlertAdminUserIds,
                                       Boolean riskMuteEnabled, Integer riskMuteLowConfidence, Long riskMuteLowSeconds,
                                       Integer riskMuteHighConfidence, Long riskMuteHighSeconds,
                                       String riskAlertGroupChannelId) {

    /** 兼容旧调用方的 12 参构造：风险监测字段全部继承（null）。 */
    public AutomationPolicyOverride(String connectionId, String channelId, Boolean enabled, Boolean mediaEnabled,
                                    String mediaProviderEndpoint, Boolean joinVerificationEnabled,
                                    List<String> approvedAnswers, List<String> rejectedAnswers,
                                    Boolean aiFallbackEnabled, Boolean failClosed, String providerCode,
                                    String modelCode) {
        this(connectionId, channelId, enabled, mediaEnabled, mediaProviderEndpoint, joinVerificationEnabled,
                approvedAnswers, rejectedAnswers, aiFallbackEnabled, failClosed, providerCode, modelCode,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 兼容旧调用方的 23 参构造：告警群继承（null）。 */
    public AutomationPolicyOverride(String connectionId, String channelId, Boolean enabled, Boolean mediaEnabled,
                                    String mediaProviderEndpoint, Boolean joinVerificationEnabled,
                                    List<String> approvedAnswers, List<String> rejectedAnswers,
                                    Boolean aiFallbackEnabled, Boolean failClosed, String providerCode,
                                    String modelCode,
                                    Boolean riskMonitorEnabled, Integer riskBatchSize,
                                    Integer riskAlertConfidence, Boolean riskAlertGroup, Boolean riskAlertAdmin,
                                    List<String> riskAlertAdminUserIds,
                                    Boolean riskMuteEnabled, Integer riskMuteLowConfidence, Long riskMuteLowSeconds,
                                    Integer riskMuteHighConfidence, Long riskMuteHighSeconds) {
        this(connectionId, channelId, enabled, mediaEnabled, mediaProviderEndpoint, joinVerificationEnabled,
                approvedAnswers, rejectedAnswers, aiFallbackEnabled, failClosed, providerCode, modelCode,
                riskMonitorEnabled, riskBatchSize, riskAlertConfidence, riskAlertGroup, riskAlertAdmin,
                riskAlertAdminUserIds, riskMuteEnabled, riskMuteLowConfidence, riskMuteLowSeconds,
                riskMuteHighConfidence, riskMuteHighSeconds, null);
    }

    public static AutomationPolicyOverride complete(AutomationPolicy policy) {
        return new AutomationPolicyOverride(policy.connectionId(), policy.channelId(), policy.enabled(),
                policy.mediaEnabled(), policy.mediaProviderEndpoint(), policy.joinVerificationEnabled(),
                policy.approvedAnswers(), policy.rejectedAnswers(), policy.aiFallbackEnabled(), policy.failClosed(),
                policy.providerCode(), policy.modelCode(),
                policy.riskMonitorEnabled(), policy.riskBatchSize(), policy.riskAlertConfidence(),
                policy.riskAlertGroup(), policy.riskAlertAdmin(), policy.riskAlertAdminUserIds(),
                policy.riskMuteEnabled(), policy.riskMuteLowConfidence(), policy.riskMuteLowSeconds(),
                policy.riskMuteHighConfidence(), policy.riskMuteHighSeconds(),
                policy.riskAlertGroupChannelId());
    }
}
