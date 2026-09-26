package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROXY protocol 一键开关：只翻转已存在的键，保留注释/缩进与文件其余内容；
 * 键缺失或类型不支持时明确报错、不写文件。
 */
class ProxyProtocolToggleTest {

    private static final String PAPER = """
            # This is a Paper config file
            _version: 29
            proxies:
              # Whether to enable BungeeCord/Velocity proxy support
              proxy-protocol: false # keep player IPs
              velocity:
                enabled: false
                secret: ''
            messages:
              kick: 'bye'
            """;

    private static final String VELOCITY = """
            # Velocity config
            bind = "0.0.0.0:25577"
            haproxy-protocol = false
            player-info-forwarding-mode = "none"
            """;

    private static final String BUNGEE = """
            listeners:
            - host: 0.0.0.0:25577
              motd: '&1Bungee'
              proxy_protocol: false
              priorities:
              - lobby
            """;

    /** 记录写入的假文件网关。 */
    private static final class FakeFiles implements ServerConfigService.InstanceFiles {
        final Map<String, String> store = new LinkedHashMap<>();
        int writes = 0;

        FakeFiles(String path, String content) {
            if (content != null) {
                store.put(path, content);
            }
        }

        @Override
        public Map<String, Object> read(String scopeKey, String instanceId, Map<String, Object> args) {
            String path = String.valueOf(args.get("path"));
            String content = store.get(path);
            if (content == null) {
                throw new IllegalStateException("file.notFound: " + path);
            }
            return Map.of("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public Map<String, Object> write(String scopeKey, String instanceId, Map<String, Object> args) {
            writes++;
            store.put(String.valueOf(args.get("path")), new String(
                    Base64.getDecoder().decode(String.valueOf(args.get("content"))), StandardCharsets.UTF_8));
            return Map.of("ok", true);
        }
    }

    @Test
    void targetDependsOnInstanceKind() {
        assertEquals("config/paper-global.yml", ProxyProtocolToggle.targetOf("paper").path());
        assertEquals("proxies", ProxyProtocolToggle.targetOf("purpur").section());
        assertEquals("haproxy-protocol", ProxyProtocolToggle.targetOf("velocity").key());
        assertEquals("proxy_protocol", ProxyProtocolToggle.targetOf("bungee").key());
        assertNull(ProxyProtocolToggle.targetOf("vanilla"));
        assertNull(ProxyProtocolToggle.targetOf("fabric"));
        assertFalse(ProxyProtocolToggle.supported("bedrock"));
    }

    @Test
    void paperNestedKeyIsFlippedWithCommentsPreserved() {
        ProxyProtocolToggle.Target target = ProxyProtocolToggle.targetOf("paper");
        assertEquals(false, ProxyProtocolToggle.current(PAPER, target));

        String updated = ProxyProtocolToggle.apply(PAPER, target, true);

        assertTrue(updated.contains("# keep player IPs"), "行尾注释保留");
        assertTrue(updated.contains("# Whether to enable BungeeCord/Velocity proxy support"), "其它注释保留");
        assertTrue(updated.contains("  proxy-protocol: true # keep player IPs"));
        assertTrue(updated.contains("    secret: ''"), "嵌套子键不受影响");
        assertTrue(updated.contains("messages:"), "段外内容不受影响");
        assertTrue(!updated.contains("proxy-protocol: false"));
        // 幂等：已是目标值时不动
        assertEquals(updated, ProxyProtocolToggle.apply(updated, target, true));
        // 关闭
        assertTrue(ProxyProtocolToggle.apply(updated, target, false).contains("proxy-protocol: false"));
    }

    @Test
    void velocityTopLevelKeyIsFlipped() {
        ProxyProtocolToggle.Target target = ProxyProtocolToggle.targetOf("velocity");
        String updated = ProxyProtocolToggle.apply(VELOCITY, target, true);
        assertTrue(updated.contains("haproxy-protocol = false") == false);
        assertTrue(updated.contains("haproxy-protocol = true"));
        assertTrue(updated.contains("player-info-forwarding-mode = \"none\""), "其它行不动");
    }

    @Test
    void bungeeListenerKeyIsFlipped() {
        ProxyProtocolToggle.Target target = ProxyProtocolToggle.targetOf("bungee");
        String updated = ProxyProtocolToggle.apply(BUNGEE, target, true);
        assertTrue(updated.contains("  proxy_protocol: true"));
        assertTrue(updated.contains("- lobby"), "列表项不动");
    }

    @Test
    void missingKeyOrEmptyContentIsRejected() {
        ProxyProtocolToggle.Target target = ProxyProtocolToggle.targetOf("paper");
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ProxyProtocolToggle.apply("proxies:\n  velocity:\n    enabled: false\n", target, true));
        assertTrue(missing.getMessage().contains("没有 proxy-protocol 键"));
        assertNull(ProxyProtocolToggle.current("", target));
    }

    @Test
    void serviceTogglesAndReportsState() {
        FakeFiles files = new FakeFiles("config/paper-global.yml", PAPER);
        ServerConfigService service = new ServerConfigService(files);

        Map<String, Object> before = service.proxyProtocolStatus("scope", "i1", "paper");
        assertEquals(true, before.get("supported"));
        assertEquals(false, before.get("enabled"));
        assertEquals("proxies.proxy-protocol", before.get("key"));

        Map<String, Object> after = service.setProxyProtocol("scope", "i1", "paper", true);
        assertEquals(true, after.get("enabled"));
        assertEquals(true, after.get("changed"));
        assertEquals(1, files.writes);
        assertTrue(files.store.get("config/paper-global.yml").contains("proxy-protocol: true"));

        // 再开一次：无变化、不再写文件
        Map<String, Object> again = service.setProxyProtocol("scope", "i1", "paper", true);
        assertEquals(false, again.get("changed"));
        assertEquals(1, files.writes);
    }

    @Test
    void serviceExplainsMissingFileAndUnsupportedKind() {
        ServerConfigService noFile = new ServerConfigService(new FakeFiles("config/paper-global.yml", null));
        Map<String, Object> status = noFile.proxyProtocolStatus("scope", "i1", "paper");
        assertEquals(false, status.get("fileExists"));
        assertTrue(String.valueOf(status.get("reason")).contains("启动一次实例"));
        assertTrue(assertThrows(RuntimeException.class,
                () -> noFile.setProxyProtocol("scope", "i1", "paper", true)).getMessage().contains("启动一次实例"));

        ServerConfigService vanilla = new ServerConfigService(new FakeFiles("server.properties", "x=1"));
        Map<String, Object> unsupported = vanilla.proxyProtocolStatus("scope", "i1", "vanilla");
        assertEquals(false, unsupported.get("supported"));
        assertTrue(assertThrows(RuntimeException.class,
                () -> vanilla.setProxyProtocol("scope", "i1", "vanilla", true)).getMessage().contains("不需要"));
    }

    @Test
    void unusedStoreIsNotTouched() {
        // 防回归：不支持的 kind 不得触发任何读写
        FakeFiles files = new FakeFiles("config/paper-global.yml", PAPER);
        ServerConfigService service = new ServerConfigService(files);
        service.proxyProtocolStatus("scope", "i1", "fabric");
        assertEquals(0, files.writes);
        assertEquals(1, files.store.size());
        assertTrue(new InMemoryDocumentStore().findById("anything", "x").isEmpty());
    }
}
