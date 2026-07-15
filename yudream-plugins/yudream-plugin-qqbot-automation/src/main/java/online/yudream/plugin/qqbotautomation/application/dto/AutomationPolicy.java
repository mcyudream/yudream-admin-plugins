package online.yudream.plugin.qqbotautomation.application.dto;

import java.util.List;

public record AutomationPolicy(String connectionId, String channelId, boolean enabled, boolean mediaEnabled,
                               String mediaProviderEndpoint, boolean joinVerificationEnabled,
                               List<String> approvedAnswers, List<String> rejectedAnswers, boolean aiFallbackEnabled,
                               boolean failClosed, String providerCode, String modelCode) {
    public static AutomationPolicy defaults(String connectionId, String channelId) {
        return new AutomationPolicy(connectionId, channelId, true, false, "", false,
                List.of(), List.of(), false, true, "", "");
    }
}
