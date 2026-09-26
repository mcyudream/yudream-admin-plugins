package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SFTP 网关设置校验：开启时端口必填合法，广告地址只收主机名/IP。 */
class SettingsServiceSftpGatewayTest {

    @Test
    void disabledOrMissingConfigNeedsNoValidation() {
        assertDoesNotThrow(() -> SettingsService.validateSftpGateway(null));
        assertDoesNotThrow(() -> SettingsService.validateSftpGateway(new PanelSettings.SftpGateway(false, 0, "")));
    }

    @Test
    void enabledGatewayRequiresValidPort() {
        assertThrows(McpanelBusinessException.class,
                () -> SettingsService.validateSftpGateway(new PanelSettings.SftpGateway(true, 0, "")));
        assertThrows(McpanelBusinessException.class,
                () -> SettingsService.validateSftpGateway(new PanelSettings.SftpGateway(true, 70_000, "")));
        assertDoesNotThrow(() -> SettingsService.validateSftpGateway(
                new PanelSettings.SftpGateway(true, 22022, "")));
    }

    @Test
    void advertisedHostAcceptsOnlyHostOrIp() {
        assertDoesNotThrow(() -> SettingsService.validateSftpGateway(
                new PanelSettings.SftpGateway(true, 22022, "panel.example.com")));
        assertDoesNotThrow(() -> SettingsService.validateSftpGateway(
                new PanelSettings.SftpGateway(true, 22022, "10.0.0.8")));

        assertThrows(McpanelBusinessException.class, () -> SettingsService.validateSftpGateway(
                new PanelSettings.SftpGateway(true, 22022, "https://panel.example.com")));
        assertThrows(McpanelBusinessException.class, () -> SettingsService.validateSftpGateway(
                new PanelSettings.SftpGateway(true, 22022, "panel.example.com:22022")));
    }
}
