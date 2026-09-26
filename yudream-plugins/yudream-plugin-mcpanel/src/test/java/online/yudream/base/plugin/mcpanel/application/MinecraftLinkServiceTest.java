package online.yudream.base.plugin.mcpanel.application;

import online.yudream.base.plugin.minecraft.api.PluginMinecraftServer;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftService;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftSubServer;
import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M5 联动单测：选项代理、绑定校验、环境变量注入、提供方缺失降级。 */
class MinecraftLinkServiceTest {

    private static PluginMinecraftServer server(String id, boolean enabled, String seasonId) {
        return new PluginMinecraftServer(id, "服-" + id, "desc", enabled, seasonId, "S1", 1L, 1L, 2L,
                List.of(new PluginMinecraftSubServer("lobby", "host:25566", 3, true, true, 0)));
    }

    private final PluginMinecraftService fake = new PluginMinecraftService() {
        @Override
        public List<PluginMinecraftServer> minecraftServers(boolean includeDisabled) {
            return List.of(server("srv-1", true, "season-9"), server("srv-2", false, null));
        }

        @Override
        public Optional<PluginMinecraftServer> minecraftServer(String serverId) {
            return minecraftServers(true).stream().filter(s -> s.id().equals(serverId)).findFirst();
        }

        @Override
        public List minecraftPlayerActivities(String serverId, int page, int size) {
            return List.of();
        }
    };

    private final MinecraftLinkService present = new MinecraftLinkService(() -> Optional.of(fake));
    private final MinecraftLinkService absent = new MinecraftLinkService(Optional::empty);

    @Test
    void optionsProxyIncludesSeasonAndSubServers() {
        Map<String, Object> options = present.options();
        assertTrue((Boolean) options.get("available"));
        List<Map<String, Object>> servers = (List<Map<String, Object>>) options.get("servers");
        assertEquals(2, servers.size());
        assertEquals("season-9", servers.get(0).get("currentSeasonId"));
        List<Map<String, Object>> subs = (List<Map<String, Object>>) servers.get(0).get("subServers");
        assertEquals("lobby", subs.get(0).get("name"));
    }

    @Test
    void absentProviderDegradesToEmptyOptions() {
        Map<String, Object> options = absent.options();
        assertFalse((Boolean) options.get("available"));
        assertTrue(((List<?>) options.get("servers")).isEmpty());
    }

    @Test
    void linkageErrorFromProviderDegradesInsteadOfSurfacing() {
        // 提供方在宿主的加载顺序晚于面板时，supplier 里的 api 类字面量解析抛 LinkageError：
        // 必须收敛为「提供方缺失」降级，而不是把 NoClassDefFoundError 漏给 HTTP 层。
        MinecraftLinkService unwired = new MinecraftLinkService(() -> {
            throw new NoClassDefFoundError("online/yudream/base/plugin/minecraft/api/PluginMinecraftService");
        });
        assertFalse(unwired.available());
        Map<String, Object> options = unwired.options();
        assertFalse((Boolean) options.get("available"));
        assertTrue(((List<?>) options.get("servers")).isEmpty());
        assertTrue(unwired.server("srv-1").isEmpty());
        assertTrue(unwired.bindingProblem("srv-1").contains("不可用"));
        assertTrue(unwired.injectEnv("srv-1").containsKey("MCSERVER_ID"));
        assertFalse(unwired.injectEnv("srv-1").containsKey("MCSERVER_TERM"));
        unwired.writebackState(boundInstance("running"));
        assertEquals(1, unwired.writebackFailureCount());
        assertEquals(0, unwired.writebackSuccessCount());
    }

    @Test
    void bindingValidation() {
        assertNull(present.bindingProblem(null));
        assertNull(present.bindingProblem(""));
        assertNull(present.bindingProblem("srv-1"));
        assertTrue(present.bindingProblem("srv-2").contains("停用"));
        assertTrue(present.bindingProblem("srv-x").contains("不存在"));
        assertTrue(absent.bindingProblem("srv-1").contains("不可用"));
    }

    @Test
    void injectEnvCarriesIdAndSeason() {
        Map<String, String> env = present.injectEnv("srv-1");
        assertEquals("srv-1", env.get("MCSERVER_ID"));
        assertEquals("season-9", env.get("MCSERVER_TERM"));
    }

    @Test
    void injectEnvOmitsSeasonWhenAbsent() {
        Map<String, String> env = present.injectEnv("srv-2");
        assertEquals("srv-2", env.get("MCSERVER_ID"));
        assertFalse(env.containsKey("MCSERVER_TERM"));
    }

    @Test
    void injectEnvEmptyForUnboundOrMissingProvider() {
        assertTrue(present.injectEnv(null).isEmpty());
        assertTrue(present.injectEnv("").isEmpty());
        // 提供方缺失时不注入（不伪造 TERM）；已有绑定保持原样由调用方负责。
        assertTrue(absent.injectEnv("srv-1").containsKey("MCSERVER_ID"));
        assertFalse(absent.injectEnv("srv-1").containsKey("MCSERVER_TERM"));
    }

    // ---------- 状态回传 ----------

    private final online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance boundInstance(String state) {
        return online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance.create(
                "inst-1", "node-1", "实例", "paper", "", "", "img",
                java.util.List.of("java"), java.util.Map.of(), 1024, 1000, 2048,
                java.util.List.of(), java.util.Map.of(), null, "", 1L)
                .withBinding("srv-1", 1L)
                .withState(state, null, 2L);
    }

    @Test
    void writebackNotifiesProviderAndCountsSuccess() {
        online.yudream.base.plugin.minecraft.api.PluginMinecraftService recorder =
                new online.yudream.base.plugin.minecraft.api.PluginMinecraftService() {
                    String lastServer;
                    String lastInstance;
                    String lastState;

                    @Override
                    public java.util.List<PluginMinecraftServer> minecraftServers(boolean includeDisabled) {
                        return java.util.List.of();
                    }

                    @Override
                    public java.util.Optional<PluginMinecraftServer> minecraftServer(String serverId) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.List minecraftPlayerActivities(String serverId, int page, int size) {
                        return java.util.List.of();
                    }

                    @Override
                    public boolean notifyPanelInstanceState(String serverId, String instanceId, String panelState, long atMs) {
                        lastServer = serverId;
                        lastInstance = instanceId;
                        lastState = panelState;
                        return true;
                    }
                };
        MinecraftLinkService link = new MinecraftLinkService(() -> java.util.Optional.of(recorder));
        link.writebackState(boundInstance("running"));
        org.junit.jupiter.api.Assertions.assertEquals(1, link.writebackSuccessCount());
        org.junit.jupiter.api.Assertions.assertEquals(0, link.writebackFailureCount());
    }

    @Test
    void writebackFailureCountedWhenProviderThrowsOrAbsent() {
        online.yudream.base.plugin.minecraft.api.PluginMinecraftService throwing =
                new online.yudream.base.plugin.minecraft.api.PluginMinecraftService() {
                    @Override
                    public java.util.List<PluginMinecraftServer> minecraftServers(boolean includeDisabled) {
                        return java.util.List.of();
                    }

                    @Override
                    public java.util.Optional<PluginMinecraftServer> minecraftServer(String serverId) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public java.util.List minecraftPlayerActivities(String serverId, int page, int size) {
                        return java.util.List.of();
                    }

                    @Override
                    public boolean notifyPanelInstanceState(String serverId, String instanceId, String panelState, long atMs) {
                        throw new IllegalArgumentException("no such server");
                    }
                };
        MinecraftLinkService failing = new MinecraftLinkService(() -> java.util.Optional.of(throwing));
        failing.writebackState(boundInstance("exited"));
        org.junit.jupiter.api.Assertions.assertEquals(1, failing.writebackFailureCount());

        absent.writebackState(boundInstance("running"));
        org.junit.jupiter.api.Assertions.assertEquals(1, absent.writebackFailureCount());
    }

    @Test
    void writebackSkipsUnboundInstance() {
        online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance unbound =
                online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance.create(
                        "inst-2", "node-1", "未绑定", "paper", "", "", "img",
                        java.util.List.of("java"), java.util.Map.of(), 1024, 1000, 2048,
                        java.util.List.of(), java.util.Map.of(), null, "", 1L);
        present.writebackState(unbound);
        present.writebackState(null);
        org.junit.jupiter.api.Assertions.assertEquals(0, present.writebackSuccessCount());
        org.junit.jupiter.api.Assertions.assertEquals(0, present.writebackFailureCount());
    }
}
