package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.acceptance.InMemorySecretStore;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.infrastructure.dns.DnsCredentials;
import online.yudream.base.plugin.mcpanel.infrastructure.dns.DnsProvider;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域名自动解析业务层：记录名/TTL 收敛/slug 冲突/分配·释放·校验，
 * 用内存驱动替换真实云商调用（签名由 provider 单测钉住）。
 */
class DomainServiceTest {

    /** 内存驱动：按「类型|记录名」存值，记录每次操作的明细供断言。 */
    private static final class FakeProvider implements DnsProvider {
        final Map<String, String> records = new LinkedHashMap<>();
        final List<String> ops = new ArrayList<>();

        @Override
        public String type() {
            return "aliyun";
        }

        @Override
        public int minTtlSeconds() {
            return 600;
        }

        @Override
        public void upsert(String name, String type, String value, int ttlSeconds) {
            records.put(type + "|" + name, value);
            ops.add("upsert:" + type + ":" + name + ":" + value + ":" + ttlSeconds);
        }

        @Override
        public void delete(String name, String type) {
            records.remove(type + "|" + name);
            ops.add("delete:" + type + ":" + name);
        }

        @Override
        public List<String> values(String name, String type) {
            String value = records.get(type + "|" + name);
            return value == null ? List.of() : List.of(value);
        }
    }

    private static final class FakeInstances implements DomainService.InstancePort {
        final List<McpanelInstance> instances = new ArrayList<>();
        final List<String> domainWrites = new ArrayList<>();

        @Override
        public List<McpanelInstance> all() {
            return instances;
        }

        @Override
        public void setDomain(String instanceId, String slug, boolean enabled) {
            domainWrites.add(instanceId + ":" + slug + ":" + enabled);
        }
    }

    private static McpanelInstance instance(String id, String name, int port) {
        return McpanelInstance.create(id, "node-1", name, "paper", "1.21", "", "img",
                List.of("java", "-jar", "server.jar"), Map.of(), 1024, 1000, 2048,
                port <= 0 ? List.of() : List.of(new McpanelInstance.PortMapping(port, 25565, "tcp")),
                Map.of(), null, "", System.currentTimeMillis());
    }

    /**
     * 组装带 DNS 配置的 SettingsService：文档直接落库（绕过保存校验），
     * 以便覆盖「驱动已选但凭据缺失」这类保存入口拦不住的降级路径；
     * 保存校验与凭据槽映射由 {@link SettingsServiceDnsTest} 覆盖。
     */
    /** 手工目标（默认等于节点直连语义）：IP → A/AAAA，主机名 → CNAME。 */
    private static DomainService.Target target(String address) {
        String type = online.yudream.base.plugin.mcpanel.domain.valobj.NodeAccess.recordTypeOf(address);
        return new DomainService.Target("manual", "自定义解析地址", type, address,
                "CNAME".equals(type) ? address : null, null);
    }

    /** 阻塞目标（打洞/未填地址）：assign/verify 必须直接拒绝并给出原因。 */
    private static DomainService.Target blockedTarget(String reason) {
        return DomainService.Target.blocked("p2p", "启动器打洞", reason);
    }

    private static SettingsService settings(String driver, String suffix, String zone, long ttl,
                                            Map<String, String> secrets) {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        NodeSecrets nodeSecrets = new NodeSecrets(new InMemorySecretStore());
        SettingsService service = new SettingsService(documents, McpanelJson.mapper(), nodeSecrets, Optional::empty);
        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("mode", driver);
        dns.put("suffix", suffix);
        dns.put("ttlSeconds", ttl);
        dns.put("provider", Map.of("zone", zone, "apiBase", ""));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("dns", dns);
        documents.save("mcpanel_settings", "settings", document);
        if (secrets != null) {
            secrets.forEach(nodeSecrets::putRaw);
        }
        return service;
    }

    private static Map<String, String> aliyunSecrets() {
        return Map.of("mcpanel:dns:aliyun-ak", "test-ak", "mcpanel:dns:aliyun-sk", "test-sk");
    }

    @Test
    void slugSanitizesInstanceName() {
        assertEquals("survival-1", DomainService.sanitize("Survival 1"));
        assertEquals("mc-server", DomainService.sanitize("  __MC__Server__  "));
        assertEquals("", DomainService.sanitize("---"));
        assertEquals("abc", DomainService.sanitize("ABC"));
    }

    @Test
    void suggestedSlugAvoidsConflictsAndFallsBackToInstanceId() {
        FakeInstances instances = new FakeInstances();
        McpanelInstance mine = instance("inst-aaaaaa", "mc", 25565);
        for (int index = 2; index <= 9; index++) {
            instances.instances.add(instance("inst-" + index, "x", 0)
                    .withDomain(index == 2 ? "mc-2" : "mc-" + index, true, 0));
        }
        instances.instances.add(instance("inst-other", "other", 0).withDomain("mc", true, 0));
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 120,
                aliyunSecrets()), instances, null, (driver, zone, apiBase, creds) -> new FakeProvider());
        assertEquals("mc-aaaaaa", service.suggestSlug(mine));
    }

    @Test
    void assignWritesARecordPlusSrvAndClampsTtl() {
        FakeInstances instances = new FakeInstances();
        FakeProvider provider = new FakeProvider();
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 120,
                aliyunSecrets()), instances, null, (driver, zone, apiBase, creds) -> provider);
        McpanelInstance server = instance("inst-1", "Survival One", 25565);

        assertTrue(service.enabled());
        assertEquals(600, service.effectiveTtl());

        Map<String, Object> result = service.assign("admin", server, target("203.0.113.10"), true);

        assertEquals("survival-one", result.get("slug"));
        assertEquals("survival-one.mc.example.com", result.get("fqdn"));
        assertEquals("upsert:A:survival-one.mc.example.com:203.0.113.10:600", provider.ops.get(0));
        assertEquals("upsert:SRV:_minecraft._tcp.survival-one.mc.example.com:0 5 25565 survival-one.mc.example.com:600",
                provider.ops.get(1));
        assertEquals(List.of("inst-1:survival-one:true"), instances.domainWrites);
    }

    @Test
    void assignSkipsSrvWhenInstanceHasNoTcpPort() {
        FakeProvider provider = new FakeProvider();
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), new FakeInstances(), null, (driver, zone, apiBase, creds) -> provider);
        Map<String, Object> result = service.assign("admin", instance("inst-2", "bedrock", 0), target("198.51.100.7"), true);
        assertEquals(false, result.get("srv"));
        assertEquals(1, provider.ops.size());
        assertTrue(provider.ops.get(0).startsWith("upsert:A:bedrock.mc.example.com"));
    }

    @Test
    void hostLookupFindsTheInstanceThatOwnsTheDomain() {
        FakeInstances instances = new FakeInstances();
        instances.instances.add(instance("inst-1", "Survival", 25565).withDomain("survival", true, 0));
        instances.instances.add(instance("inst-2", "Creative", 25565).withDomain("creative", true, 0));
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), instances, null, (driver, zone, apiBase, creds) -> new FakeProvider());

        // 启动器拿到的是玩家输入形态：域名、带端口、大小写混杂、末尾点
        assertEquals("inst-1", service.instanceForHost("survival.mc.example.com").orElseThrow().id());
        assertEquals("inst-1", service.instanceForHost("survival.mc.example.com:25565").orElseThrow().id());
        assertEquals("inst-1", service.instanceForHost("  SURVIVAL.MC.Example.com.  ").orElseThrow().id());
        // IPv6 字面量不是域名，不参与匹配
        assertTrue(service.instanceForHost("[::1]:25565").isEmpty());
        assertTrue(service.instanceForHost("unknown.mc.example.com").isEmpty());
        assertTrue(service.instanceForHost("survival.other.example.com").isEmpty());
        assertTrue(service.instanceForHost(null).isEmpty());
        assertTrue(service.instanceForHost("").isEmpty());
    }

    @Test
    void hostLookupIgnoresUnassignedAndPreviewNames() {
        FakeInstances instances = new FakeInstances();
        // 未分配域名（domainEnabled=false）与只有预览名的实例都不算数
        instances.instances.add(instance("inst-1", "Survival", 25565).withDomain("survival", false, 0));
        instances.instances.add(instance("inst-2", "Creative", 25565));
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), instances, null, (driver, zone, apiBase, creds) -> new FakeProvider());

        assertTrue(service.instanceForHost("survival.mc.example.com").isEmpty());
        assertTrue(service.instanceForHost("creative.mc.example.com").isEmpty(),
                "推导出的预览名不是真实记录，不能被反查命中");
    }

    @Test
    void hostLookupNeedsAConfiguredSuffix() {
        FakeInstances instances = new FakeInstances();
        instances.instances.add(instance("inst-1", "Survival", 25565).withDomain("survival", true, 0));
        DomainService service = new DomainService(settings("aliyun", "", "example.com", 600, aliyunSecrets()),
                instances, null, (driver, zone, apiBase, creds) -> new FakeProvider());

        assertTrue(service.instanceForHost("survival").isEmpty());
    }

    @Test
    void normalizesHostsForLookup() {
        assertEquals("play.example.com", DomainService.normalizeHost("Play.Example.COM"));
        assertEquals("play.example.com", DomainService.normalizeHost("play.example.com:25565"));
        assertEquals("play.example.com", DomainService.normalizeHost("play.example.com."));
        assertEquals("::1", DomainService.normalizeHost("[::1]:25565"));
        assertEquals("", DomainService.normalizeHost("   "));
        assertEquals("", DomainService.normalizeHost(null));
    }

    @Test
    void blockedTargetIsRejectedWithItsReason() {
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), new FakeInstances(), null, (driver, zone, apiBase, creds) -> new FakeProvider());
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.assign("admin", instance("inst-3", "mc", 25565),
                        blockedTarget("该节点为启动器打洞接入：不写公网解析"), true));
        assertTrue(error.getMessage().contains("打洞"));
        assertThrows(McpanelBusinessException.class,
                () -> service.verify(instance("inst-3", "mc", 25565), blockedTarget("该节点为启动器打洞接入"), true));
    }

    @Test
    void hostnameTargetWritesCnameAndSrvPointsAtRealHost() {
        FakeProvider provider = new FakeProvider();
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), new FakeInstances(), null, (driver, zone, apiBase, creds) -> provider);
        McpanelInstance server = instance("inst-12", "Survival", 25565);

        Map<String, Object> result = service.assign("admin", server, target("play.example.net"), true);

        assertEquals("CNAME", result.get("recordType"));
        assertEquals("upsert:CNAME:survival.mc.example.com:play.example.net:600", provider.ops.get(0));
        // RFC 2181：CNAME 别名不能作为 SRV 目标，SRV 直指真实主机。
        assertEquals("upsert:SRV:_minecraft._tcp.survival.mc.example.com:0 5 25565 play.example.net:600",
                provider.ops.get(1));
    }

    @Test
    void ipv6TargetWritesAaaaRecord() {
        FakeProvider provider = new FakeProvider();
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), new FakeInstances(), null, (driver, zone, apiBase, creds) -> provider);
        service.assign("admin", instance("inst-13", "v6", 25565), target("2001:db8::1"), true);
        assertEquals("upsert:AAAA:v6.mc.example.com:2001:db8::1:600", provider.ops.get(0));
        assertEquals("upsert:SRV:_minecraft._tcp.v6.mc.example.com:0 5 25565 v6.mc.example.com:600",
                provider.ops.get(1));
    }

    @Test
    void assignedSlugSurvivesRename() {
        FakeProvider provider = new FakeProvider();
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), new FakeInstances(), null, (driver, zone, apiBase, creds) -> provider);
        McpanelInstance renamed = McpanelInstance.create("inst-4", "node-1", "renamed-server", "paper", "1.21", "",
                "img", List.of("java"), Map.of(), 1024, 1000, 2048,
                List.of(new McpanelInstance.PortMapping(25566, 25565, "tcp")), Map.of(), null, "",
                System.currentTimeMillis()).withDomain("old-name", true, 0);
        assertEquals("old-name.mc.example.com", service.fqdn(renamed));
        service.assign("admin", renamed, target("203.0.113.11"), true);
        assertEquals("old-name", ((Map<?, ?>) service.view(renamed)).get("slug"));
    }

    @Test
    void releaseDeletesRecordsAndDisablesSwitch() {
        FakeInstances instances = new FakeInstances();
        FakeProvider provider = new FakeProvider();
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), instances, null, (driver, zone, apiBase, creds) -> provider);
        McpanelInstance server = instance("inst-5", "mc", 25565).withDomain("mc", true, 0);
        provider.records.put("A|mc.mc.example.com", "203.0.113.12");
        provider.records.put("SRV|_minecraft._tcp.mc.mc.example.com", "0 5 25565 mc.mc.example.com");

        service.release("admin", server);

        assertTrue(provider.records.isEmpty());
        assertEquals(List.of("delete:SRV:_minecraft._tcp.mc.mc.example.com", "delete:A:mc.mc.example.com",
                        "delete:AAAA:mc.mc.example.com", "delete:CNAME:mc.mc.example.com"),
                provider.ops, "接入方式可能变过：三种地址记录类型都要清");
        assertEquals(List.of("inst-5:mc:false"), instances.domainWrites);
    }

    @Test
    void verifyComparesProviderStateWithExpectation() {
        FakeProvider provider = new FakeProvider();
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), new FakeInstances(), null, (driver, zone, apiBase, creds) -> provider);
        McpanelInstance server = instance("inst-6", "mc", 25565).withDomain("mc", true, 0);

        Map<String, Object> missing = service.verify(server, target("203.0.113.13"), true);
        assertEquals(false, missing.get("ok"));

        provider.records.put("A|mc.mc.example.com", "203.0.113.13");
        provider.records.put("SRV|_minecraft._tcp.mc.mc.example.com", "0 5 25565 mc.mc.example.com");
        Map<String, Object> ok = service.verify(server, target("203.0.113.13"), true);
        assertEquals(true, ok.get("ok"));
        assertEquals(true, ok.get("aOk"));
        assertEquals(true, ok.get("srvOk"));
    }

    @Test
    void driverOffDisablesEverythingWithReason() {
        DomainService service = new DomainService(settings("off", "", "", 120, null),
                new FakeInstances(), null, (driver, zone, apiBase, creds) -> new FakeProvider());
        assertFalse(service.enabled());
        assertTrue(service.disabledReason().contains("未启用"));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.assign("admin", instance("inst-7", "mc", 25565), target("203.0.113.14"), true));
        assertEquals("domain.disabled", error.code());
    }

    @Test
    void missingCredentialsBlockAssignWithReadableReason() {
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600, null),
                new FakeInstances(), null, (driver, zone, apiBase, creds) -> new FakeProvider());
        assertTrue(service.disabledReason().contains("AccessKeyId"));
        assertThrows(McpanelBusinessException.class,
                () -> service.assign("admin", instance("inst-8", "mc", 25565), target("203.0.113.15"), true));
    }

    @Test
    void viewExposesConnectAddressAndPendingSlug() {
        DomainService service = new DomainService(settings("aliyun", "mc.example.com", "example.com", 600,
                aliyunSecrets()), new FakeInstances(), null, (driver, zone, apiBase, creds) -> new FakeProvider());
        Map<String, Object> pending = service.view(instance("inst-9", "My Server", 25565));
        assertEquals(false, pending.get("assigned"));
        assertEquals("my-server", pending.get("suggestedSlug"));
        assertEquals("my-server.mc.example.com", pending.get("fqdn"));

        Map<String, Object> assigned = service.view(instance("inst-10", "My Server", 25565).withDomain("ms", true, 0));
        assertEquals(true, assigned.get("assigned"));
        assertEquals("ms.mc.example.com", assigned.get("connectAddress"), "默认端口 25565 不显示端口");
        // 非默认端口才带端口
        Map<String, Object> custom = service.view(instance("inst-14", "My Server", 25580).withDomain("ms", true, 0));
        assertEquals("ms.mc.example.com:25580", custom.get("connectAddress"));
        assertEquals("阿里云云解析", assigned.get("driverLabel"));
        assertEquals(600, assigned.get("ttlSeconds"));    }

    @Test
    void credentialsReachProviderFactory() {
        List<DnsCredentials> seen = new ArrayList<>();
        DomainService service = new DomainService(settings("dnspod", "mc.example.com", "example.com", 600,
                Map.of("mcpanel:dns:tencent-sid", "sid", "mcpanel:dns:tencent-skey", "skey")), new FakeInstances(),
                null, (driver, zone, apiBase, creds) -> {
                    seen.add(creds);
                    return new FakeProvider();
                });
        service.assign("admin", instance("inst-11", "mc", 25565), target("203.0.113.16"), false);
        assertEquals(1, seen.size());
        assertEquals("sid", seen.get(0).tencentSecretId());
        assertEquals("skey", seen.get(0).tencentSecretKey());
        assertNull(service.disabledReason());
    }
}
