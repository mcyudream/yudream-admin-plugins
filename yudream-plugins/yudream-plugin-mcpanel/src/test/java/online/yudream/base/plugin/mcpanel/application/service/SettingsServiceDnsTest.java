package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.acceptance.InMemorySecretStore;
import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 域名自动解析的设置面：凭据分槽保存/清除、视图只回显「已配置」、保存即校验驱动可用性。 */
class SettingsServiceDnsTest {

    private static SettingsService service() {
        return new SettingsService(new InMemoryDocumentStore(), McpanelJson.mapper(),
                new NodeSecrets(new InMemorySecretStore()), Optional::empty);
    }

    private static PanelSettings withDns(String driver, String suffix, String zone, long ttl) {
        PanelSettings defaults = PanelSettings.defaults();
        return new PanelSettings(defaults.authlib(), defaults.playtime(), defaults.modpack(),
                new PanelSettings.Dns(driver, suffix, ttl, new PanelSettings.Dns.Provider(zone, "")),
                defaults.entry(),
                defaults.tenancy(), defaults.contribution(), defaults.p2p(),
                defaults.coreDownload(), defaults.sftpGateway());
    }

    @Test
    void credentialsAreStoredPerDriverAndViewOnlyEchoesFlags() {
        SettingsService service = service();
        Map<String, Object> view = service.save(withDns("aliyun", "mc.example.com", "example.com", 120),
                Map.of("dnsAliyunAccessKeyId", "ak", "dnsAliyunAccessKeySecret", "sk"));

        assertEquals("ak", service.dnsCredentials().aliyunAccessKeyId());
        assertEquals("sk", service.dnsCredentials().aliyunAccessKeySecret());
        Map<?, ?> dns = (Map<?, ?>) view.get("dns");
        Map<?, ?> credentials = (Map<?, ?>) dns.get("credentials");
        assertEquals(true, credentials.get("aliyunConfigured"));
        assertEquals(false, credentials.get("cloudflareTokenConfigured"));
        assertEquals(false, credentials.get("tencentConfigured"));
        // 真值绝不出现在视图里
        assertTrue(!view.toString().contains("\"ak\""));
    }

    @Test
    void clearingCredentialsRequiresDriverToBeOff() {
        SettingsService service = service();
        service.save(withDns("dnspod", "mc.example.com", "example.com", 600),
                Map.of("dnsTencentSecretId", "sid", "dnsTencentSecretKey", "skey"));
        assertEquals("sid", service.dnsCredentials().tencentSecretId());

        // 驱动仍启用时清空凭据：直接拦住，不留半配置的驱动
        assertThrows(McpanelBusinessException.class, () -> service.save(
                withDns("dnspod", "mc.example.com", "example.com", 600), Map.of("dnsTencentSecretId", "")));

        // 先关驱动（或换驱动）再清空：允许
        service.save(withDns("off", "", "", 600), Map.of("dnsTencentSecretId", "", "dnsTencentSecretKey", ""));
        assertEquals("", service.dnsCredentials().tencentSecretId());
        assertEquals("", service.dnsCredentials().tencentSecretKey());
    }

    @Test
    void unknownSecretKeysAreIgnored() {
        SettingsService service = service();
        assertDoesNotThrow(() -> service.save(withDns("off", "", "", 120),
                Map.of("dnsSomebodyElse", "value", "modpackCfKey", "")));
        assertEquals("", service.dnsCredentials().cloudflareToken());
    }

    @Test
    void savingEnabledDriverWithoutCredentialsIsRejectedUpFront() {
        SettingsService service = service();
        McpanelBusinessException missing = assertThrows(McpanelBusinessException.class,
                () -> service.save(withDns("aliyun", "mc.example.com", "example.com", 120), Map.of()));
        assertTrue(missing.getMessage().contains("AccessKeyId"));

        // 同一驱动补齐凭据即可保存
        assertDoesNotThrow(() -> service.save(withDns("cloudflare", "mc.example.com", "zone-id-123", 120),
                Map.of("dnsCloudflareToken", "cf-token")));
    }

    @Test
    void suffixMustLiveInsideRootDomain() {
        SettingsService service = service();
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.save(withDns("aliyun", "mc.other.com", "example.com", 120),
                        Map.of("dnsAliyunAccessKeyId", "ak", "dnsAliyunAccessKeySecret", "sk")));
        assertTrue(error.getMessage().contains("不在根域名"));

        // Cloudflare 的 Zone ID 与后缀无包含关系，不做该校验
        assertDoesNotThrow(() -> service.save(withDns("cloudflare", "mc.other.com", "zone-id-123", 120),
                Map.of("dnsCloudflareToken", "cf-token")));
    }

    @Test
    void unknownDriverIsRejected() {
        SettingsService service = service();
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.save(withDns("route53", "mc.example.com", "example.com", 120), Map.of()));
        assertTrue(error.getMessage().contains("cloudflare"));
    }
}
