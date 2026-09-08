package online.yudream.plugin.qqbotautomation.application.dto;

/**
 * Administrator-maintained Milky shared-media locations stored in the plugin SecretStore.
 * A blank hostDirectory clears it and disables local file delivery.
 */
public record MediaStorageSettingsRequest(String hostDirectory, String containerDirectory) {
}
