package online.yudream.plugin.qqbotautomation.application.dto;

/**
 * An administrator-requested media parsing job that sends its result to the selected QQ group.
 */
public record MediaJobTestRequest(String connectionId, String channelId, String sourceUrl) {
}
