package online.yudream.plugin.qqbotautomation.application.dto;

import java.util.List;

public record AutomationPolicy(String connectionId, String channelId, boolean enabled, boolean mediaEnabled,
                               String mediaProviderEndpoint, boolean joinVerificationEnabled,
                               List<String> approvedAnswers, List<String> rejectedAnswers, boolean aiFallbackEnabled,
                               boolean failClosed, String providerCode, String modelCode,
                               boolean riskMonitorEnabled, int riskBatchSize,
                               int riskAlertConfidence, boolean riskAlertGroup, boolean riskAlertAdmin,
                               List<String> riskAlertAdminUserIds,
                               boolean riskMuteEnabled, int riskMuteLowConfidence, long riskMuteLowSeconds,
                               int riskMuteHighConfidence, long riskMuteHighSeconds) {
    public static final int DEFAULT_RISK_BATCH_SIZE = 20;
    public static final int DEFAULT_RISK_ALERT_CONFIDENCE = 80;
    public static final int DEFAULT_RISK_MUTE_LOW_CONFIDENCE = 70;
    public static final long DEFAULT_RISK_MUTE_LOW_SECONDS = 600;
    public static final int DEFAULT_RISK_MUTE_HIGH_CONFIDENCE = 90;
    public static final long DEFAULT_RISK_MUTE_HIGH_SECONDS = 86400;

    public static AutomationPolicy defaults(String connectionId, String channelId) {
        return new AutomationPolicy(connectionId, channelId, true, false, "", false,
                List.of(), List.of(), false, true, "", "",
                false, DEFAULT_RISK_BATCH_SIZE, DEFAULT_RISK_ALERT_CONFIDENCE, false, false, List.of(),
                false, DEFAULT_RISK_MUTE_LOW_CONFIDENCE, DEFAULT_RISK_MUTE_LOW_SECONDS,
                DEFAULT_RISK_MUTE_HIGH_CONFIDENCE, DEFAULT_RISK_MUTE_HIGH_SECONDS);
    }

    public static AutomationPolicy connectionDefaults(String connectionId) {
        return defaults(connectionId, "");
    }

    /** 兼容旧调用方的 12 参构造：风险监测字段全部取默认值（关闭）。 */
    public AutomationPolicy(String connectionId, String channelId, boolean enabled, boolean mediaEnabled,
                            String mediaProviderEndpoint, boolean joinVerificationEnabled,
                            List<String> approvedAnswers, List<String> rejectedAnswers, boolean aiFallbackEnabled,
                            boolean failClosed, String providerCode, String modelCode) {
        this(connectionId, channelId, enabled, mediaEnabled, mediaProviderEndpoint, joinVerificationEnabled,
                approvedAnswers, rejectedAnswers, aiFallbackEnabled, failClosed, providerCode, modelCode,
                false, DEFAULT_RISK_BATCH_SIZE, DEFAULT_RISK_ALERT_CONFIDENCE, false, false, List.of(),
                false, DEFAULT_RISK_MUTE_LOW_CONFIDENCE, DEFAULT_RISK_MUTE_LOW_SECONDS,
                DEFAULT_RISK_MUTE_HIGH_CONFIDENCE, DEFAULT_RISK_MUTE_HIGH_SECONDS);
    }

    /** 按置信度选择禁言时长（秒）；未达低档阈值返回 0 表示不禁言。 */
    public long muteSecondsForConfidence(int confidence) {
        if (confidence >= riskMuteHighConfidence) {
            return riskMuteHighSeconds;
        }
        if (confidence >= riskMuteLowConfidence) {
            return riskMuteLowSeconds;
        }
        return 0;
    }
}
