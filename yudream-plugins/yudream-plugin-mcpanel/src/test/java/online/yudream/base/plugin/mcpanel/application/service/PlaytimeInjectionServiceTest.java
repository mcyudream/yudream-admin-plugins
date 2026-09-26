package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在线时长注入（实例粒度）：制品矩阵匹配、按形态放入插件/模组目录、开关链路（无 Mockito，纯 fake）。 */
class PlaytimeInjectionServiceTest {

    private static final byte[] JAR = "fake-playtime-jar".getBytes(StandardCharsets.UTF_8);
    private static final String JAR_SHA = sha256(JAR);

    private McpanelInstanceRepository instances;
    private SettingsService settingsService;
    private FakeFileStore fileStore;
    /** 模拟实例文件系统：path → 内容；listNames 按目录前缀返回文件名。 */
    private final TreeMap<String, byte[]> instanceFiles = new TreeMap<>();
    private final List<String> deletes = new ArrayList<>();
    private PlaytimeInjectionService service;

    @BeforeEach
    void setUp() {
        instanceFiles.clear();
        deletes.clear();
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        online.yudream.base.plugin.spi.system.secret.PluginSecretStore emptySecrets =
                new online.yudream.base.plugin.spi.system.secret.PluginSecretStore() {
                    @Override
                    public void put(String key, byte[] secret) { }

                    @Override
                    public java.util.Optional<byte[]> get(String key) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public boolean delete(String key) {
                        return false;
                    }
                };
        settingsService = new SettingsService(documents, McpanelJson.mapper(), new NodeSecrets(emptySecrets),
                () -> Optional.empty());
        fileStore = new FakeFileStore();
        service = new PlaytimeInjectionService(instances, settingsService,
                new ArtifactStoreService(fileStore),
                new PlaytimeInjectionService.InstanceFileOps() {
                    @Override
                    public void upload(String scopeKey, String instanceId, String path, byte[] data, String sha256Hex) {
                        instanceFiles.put(path, data);
                    }

                    @Override
                    public void delete(String scopeKey, String instanceId, String path) {
                        deletes.add(path);
                        instanceFiles.remove(path);
                    }

                    @Override
                    public List<String> listNames(String scopeKey, String instanceId, String dir) {
                        String prefix = dir.endsWith("/") ? dir : dir + "/";
                        return instanceFiles.subMap(prefix, prefix + Character.MAX_VALUE).keySet().stream()
                                .map(path -> path.substring(prefix.length()))
                                .filter(name -> !name.contains("/"))
                                .toList();
                    }
                },
                (actor, action, targetType, targetId, detail, tenantId) -> { });
    }

    /** 平台文件存储内存 fake：put/get 按对象键映射。 */
    private static final class FakeFileStore implements PluginFileStore {
        final java.util.Map<String, byte[]> objects = new java.util.HashMap<>();

        @Override
        public String put(String objectKey, InputStream inputStream, long contentLength, String contentType) {
            try {
                objects.put(objectKey, inputStream.readAllBytes());
            }
            catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
            return objectKey;
        }

        @Override
        public PluginStoredFile get(String objectKey) {
            byte[] data = objects.get(objectKey);
            return data == null ? null : new PluginStoredFile(objectKey, "application/java-archive",
                    (long) data.length, new ByteArrayInputStream(data));
        }

        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static McpanelInstance instance(String id, String kind, String mcVersion, String state) {
        return McpanelInstance.create(id, "nodeA", "inst-" + id, kind, mcVersion, "", "img",
                List.of("java", "-jar", "server.jar"), Map.of(), 512, 500, 1024, List.of(), Map.of(),
                null, "", 1L).withState(state, null, 1L);
    }

    /** 经真实 save 路径写设置（会过 validate），playtime 制品矩阵两个条目、fileId 指向制品库。 */
    private void settings(boolean enabled) {
        PanelSettings base = PanelSettings.defaults();
        List<PanelSettings.Artifact> artifacts = List.of(
                new PanelSettings.Artifact("时长插件 1.20-1.21", "plugin", List.of(),
                        "1.20", "1.21", "mcpanel/artifacts/playtime-plugin-old.jar", null, null),
                new PanelSettings.Artifact("时长插件 1.21+", "plugin", List.of(),
                        "1.21.1", null, "mcpanel/artifacts/playtime-plugin-new.jar", null, null),
                new PanelSettings.Artifact("时长 Fabric 模组", "mod", List.of("fabric"),
                        null, null, "mcpanel/artifacts/playtime-fabric.jar", null, null),
                new PanelSettings.Artifact("时长 Forge 模组", "mod", List.of("forge"),
                        null, null, "mcpanel/artifacts/playtime-forge.jar", null, null));
        settingsService.save(new PanelSettings(
                base.authlib(), new PanelSettings.Playtime(enabled, artifacts), base.modpack(),
                base.dns(), base.entry(), base.tenancy(), base.contribution(), base.p2p(),
                base.coreDownload(), base.sftpGateway()), null);
        fileStore.objects.put("mcpanel/artifacts/playtime-plugin-old.jar", JAR);
        fileStore.objects.put("mcpanel/artifacts/playtime-plugin-new.jar", JAR);
        fileStore.objects.put("mcpanel/artifacts/playtime-fabric.jar", JAR);
        fileStore.objects.put("mcpanel/artifacts/playtime-forge.jar", JAR);
    }

    @Test
    void pickMatchesArtifactByFormLoaderAndMcRange() {
        // paper 1.21.4：1.20-1.21 不含 1.21.4 → 命中 1.21.1+ 条目
        PanelSettings.Artifact paper = PlaytimeInjectionService.pick(
                settingsArtifacts(), "paper", "1.21.4");
        assertEquals("时长插件 1.21+", paper.name());
        // fabric 1.20：命中 mod/fabric（版本不限）
        PanelSettings.Artifact fabric = PlaytimeInjectionService.pick(
                settingsArtifacts(), "fabric", "1.20");
        assertEquals("时长 Fabric 模组", fabric.name());
        // forge：命中 mod/forge
        assertEquals("时长 Forge 模组",
                PlaytimeInjectionService.pick(settingsArtifacts(), "forge", "1.20").name());
        // velocity（plugin 形态）：版本号 3.3.0 按段数值比较落在 [1.21.1, ∞) 内 → 命中 1.21.1+ 条目
        // （矩阵对代理类实例按其自身版本号比较；需精确控制时给代理配独立条目）
        assertEquals("时长插件 1.21+",
                PlaytimeInjectionService.pick(settingsArtifacts(), "velocity", "3.3.0").name());
        // paper 1.19 低于全部区间 → 无匹配
        assertEquals(null, PlaytimeInjectionService.pick(settingsArtifacts(), "paper", "1.19"));
    }

    @SuppressWarnings("unchecked")
    private List<PanelSettings.Artifact> settingsArtifacts() {
        // 与 settings(true) 相同的矩阵（仅用于 pick 纯函数断言）
        return List.of(
                new PanelSettings.Artifact("时长插件 1.20-1.21", "plugin", List.of(),
                        "1.20", "1.21", "mcpanel/artifacts/playtime-plugin-old.jar", null, null),
                new PanelSettings.Artifact("时长插件 1.21+", "plugin", List.of(),
                        "1.21.1", null, "mcpanel/artifacts/playtime-plugin-new.jar", null, null),
                new PanelSettings.Artifact("时长 Fabric 模组", "mod", List.of("fabric"),
                        null, null, "mcpanel/artifacts/playtime-fabric.jar", null, null),
                new PanelSettings.Artifact("时长 Forge 模组", "mod", List.of("forge"),
                        null, null, "mcpanel/artifacts/playtime-forge.jar", null, null));
    }

    @Test
    void versionRangeBoundarySemantics() {
        assertTrue(PlaytimeInjectionService.versionInRange("1.20", "1.20", "1.21"));
        assertTrue(PlaytimeInjectionService.versionInRange("1.21.4", "1.21.1", null));
        assertFalse(PlaytimeInjectionService.versionInRange("1.21.4", "1.20", "1.21"));
        assertTrue(PlaytimeInjectionService.versionInRange("1.20", null, null));
        // 实例版本为空：仅双侧不限的制品匹配
        assertFalse(PlaytimeInjectionService.versionInRange(null, "1.20", null));
        assertTrue(PlaytimeInjectionService.versionInRange(null, null, null));
    }

    @Test
    void applyEnableDownloadsMatchedArtifactIntoPluginsDir() {
        instances.save(instance("i1", "paper", "1.21.4", "exited"));
        settings(true);

        Map<String, Object> view = service.apply("admin", "user:1", "i1", true);

        assertEquals(Boolean.TRUE, view.get("enabled"));
        assertEquals(Boolean.TRUE, view.get("supported"));
        assertEquals("plugins", view.get("dir"));
        assertTrue(instanceFiles.containsKey("plugins/" + PlaytimeInjectionService.AGENT_JAR));
        assertEquals("时长插件 1.21+", String.valueOf(view.get("matchedName")));
    }

    @Test
    void applyEnableForFabricGoesToModsDir() {
        instances.save(instance("i1", "fabric", "1.20", "exited"));
        settings(true);

        Map<String, Object> view = service.apply("admin", "user:1", "i1", true);

        assertEquals("mods", view.get("dir"));
        assertTrue(instanceFiles.containsKey("mods/" + PlaytimeInjectionService.AGENT_JAR));
        assertEquals("时长 Fabric 模组", String.valueOf(view.get("matchedName")));
    }

    @Test
    void applyDisableDeletesArtifactFile() {
        instances.save(instance("i1", "paper", "1.21.4", "exited"));
        settings(true);
        service.apply("admin", "user:1", "i1", true);

        Map<String, Object> view = service.apply("admin", "user:1", "i1", false);

        assertEquals(Boolean.FALSE, view.get("enabled"));
        assertTrue(deletes.contains("plugins/" + PlaytimeInjectionService.AGENT_JAR));
        assertFalse(instanceFiles.containsKey("plugins/" + PlaytimeInjectionService.AGENT_JAR));
    }

    @Test
    void applyIsNoOpWhenAlreadyInDesiredState() {
        instances.save(instance("i1", "paper", "1.21.4", "exited"));
        settings(true);
        service.apply("admin", "user:1", "i1", true);
        int uploadsBefore = instanceFiles.size();

        service.apply("admin", "user:1", "i1", true);

        assertEquals(uploadsBefore, instanceFiles.size());
    }

    @Test
    void applyRejectsRunningInstance() {
        instances.save(instance("i1", "paper", "1.21.4", "running"));
        settings(true);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.apply("admin", "user:1", "i1", true));
        assertEquals("instance-running", error.code());
        assertTrue(instanceFiles.isEmpty());
    }

    @Test
    void applyRejectsWhenDisabledOrNoMatchingArtifact() {
        instances.save(instance("i1", "paper", "1.21.4", "exited"));
        settings(false);
        assertThrows(McpanelBusinessException.class, () -> service.apply("admin", "user:1", "i1", true));

        // paper 1.19 低于全部制品区间 → 无匹配报错
        instances.save(instance("i2", "paper", "1.19", "exited"));
        settings(true);
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.apply("admin", "user:1", "i2", true));
        assertTrue(String.valueOf(error.getMessage()).contains("没有匹配"));
    }

    @Test
    void viewReportsUnsupportedWhenDisabled() {
        instances.save(instance("i1", "paper", "1.21.4", "exited"));
        PanelSettings base = PanelSettings.defaults();
        // enabled=false + 空矩阵（enabled=true 至少需一个制品，设置校验会拦截）
        settingsService.save(new PanelSettings(
                base.authlib(), new PanelSettings.Playtime(false, List.of()), base.modpack(),
                base.dns(), base.entry(), base.tenancy(), base.contribution(), base.p2p(),
                base.coreDownload(), base.sftpGateway()), null);

        Map<String, Object> view = service.view("user:1", "i1");

        assertEquals(Boolean.FALSE, view.get("supported"));
        assertTrue(String.valueOf(view.get("reason")).contains("未启用"));
    }
}
