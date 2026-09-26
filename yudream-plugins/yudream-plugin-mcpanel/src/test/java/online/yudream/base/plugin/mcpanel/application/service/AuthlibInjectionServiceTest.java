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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** authlib 注入（实例粒度）：命令注入/剥离、开关前置校验与下发链路（无 Mockito，纯 fake）。 */
class AuthlibInjectionServiceTest {

    private static final String API_ROOT = "https://mc.example.com/api/plugins/authlib-injector";
    private static final byte[] JAR = "fake-authlib-jar".getBytes(StandardCharsets.UTF_8);
    private static final String JAR_SHA = sha256(JAR);

    private McpanelInstanceRepository instances;
    private SettingsService settingsService;
    private FakeFileStore fileStore;
    private AuthlibInjectionService service;
    /** 记录型 fake：记录 upload/update 调用，update 原样返回 view。 */
    private AuthlibInjectionService.InstanceMutations mutations;

    private record UpdateCall(String actor, String scopeKey, String instanceId, McpanelInstance spec) {
    }

    private record UploadCall(String scopeKey, String instanceId, String path, byte[] data, String sha256Hex) {
    }

    private final List<UploadCall> uploads = new ArrayList<>();
    private final List<UpdateCall> updates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        InMemoryDocumentStore documents = new InMemoryDocumentStore();
        instances = new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
        // 空 SecretStore：save() 的视图构建会读 CF key 标记，测试不落任何密钥
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
        service = new AuthlibInjectionService(instances, settingsService,
                new ArtifactStoreService(fileStore),
                new AuthlibLinkService(() -> Optional.empty()),
                new AuthlibInjectionService.InstanceMutations() {
                    @Override
                    public void upload(String scopeKey, String instanceId, String path, byte[] data, String sha256Hex) {
                        uploads.add(new UploadCall(scopeKey, instanceId, path, data, sha256Hex));
                    }

                    @Override
                    public Map<String, Object> update(String actor, String scopeKey, String instanceId, McpanelInstance spec) {
                        updates.add(new UpdateCall(actor, scopeKey, instanceId, spec));
                        // 与真实 update 一致：规格落库（供后续 view 读取）
                        instances.save(spec);
                        return Map.of();
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

    private static McpanelInstance instance(String id, String state, List<String> command) {
        return McpanelInstance.create(id, "nodeA", "inst-" + id, "paper", "1.21", "", "img",
                List.of(), Map.of(), 512, 500, 1024, List.of(), Map.of(), null, "", 1L)
                .withState(state, null, 1L)
                .withCommand(command, 1L);
    }

    /** 经真实 save 路径写设置（会过 validate），fileId 用合法的制品库前缀。 */
    private void settings(boolean enabled, String apiRoot, String sha256) {
        // 组件不能为 null：文档存储拒 null 值，用 defaults() 补齐其余组件
        PanelSettings base = PanelSettings.defaults();
        settingsService.save(new PanelSettings(
                new PanelSettings.Authlib(enabled, "mcpanel/artifacts/authlib-injector.jar",
                        null, apiRoot, sha256),
                base.playtime(), base.modpack(), base.dns(), base.entry(), base.tenancy(),
                base.contribution(), base.p2p(), base.coreDownload(), base.sftpGateway()), null);
        fileStore.objects.put("mcpanel/artifacts/authlib-injector.jar", JAR);
    }

    @Test
    void injectAppendsAgentAfterJavaAndIsIdempotent() {
        List<String> base = List.of("java", "-Xmx1G", "-jar", "server.jar");
        List<String> once = AuthlibInjectionService.inject(base, API_ROOT);
        assertEquals(List.of("java", AuthlibInjectionService.AGENT_PREFIX + API_ROOT,
                "-Xmx1G", "-jar", "server.jar"), once);
        // apiRoot 变化：替换而不是叠加
        List<String> twice = AuthlibInjectionService.inject(once, "https://new.example.com/ygg");
        assertEquals(List.of("java", AuthlibInjectionService.AGENT_PREFIX + "https://new.example.com/ygg",
                "-Xmx1G", "-jar", "server.jar"), twice);
        assertTrue(AuthlibInjectionService.isInjected(instance("i1", "exited", twice)));
    }

    @Test
    void injectRejectsNonJavaCommand() {
        assertThrows(McpanelBusinessException.class,
                () -> AuthlibInjectionService.inject(List.of("bash", "run.sh"), API_ROOT));
    }

    @Test
    void stripRemovesInjectedArgument() {
        List<String> injected = AuthlibInjectionService.inject(
                List.of("java", "-jar", "server.jar"), API_ROOT);
        assertEquals(List.of("java", "-jar", "server.jar"), AuthlibInjectionService.strip(injected));
        assertFalse(AuthlibInjectionService.isInjected(instance("i1", "exited",
                AuthlibInjectionService.strip(injected))));
    }

    @Test
    void applyRejectsRunningInstance() {
        instances.save(instance("i1", "running", List.of("java", "-jar", "server.jar")));
        McpanelBusinessException error = assertThrows(McpanelBusinessException.class,
                () -> service.apply("admin", "user:1", "i1", true));
        assertEquals("instance-running", error.code());
        assertTrue(uploads.isEmpty());
        assertTrue(updates.isEmpty());
    }

    @Test
    void applyEnableUploadsJarAndInjectsCommand() {
        instances.save(instance("i1", "exited", List.of("java", "-jar", "server.jar")));
        settings(true, API_ROOT, JAR_SHA);

        Map<String, Object> view = service.apply("admin", "user:1", "i1", true);

        assertEquals(1, uploads.size());
        assertEquals("authlib-injector.jar", uploads.get(0).path());
        assertEquals(JAR_SHA, uploads.get(0).sha256Hex());
        assertEquals(1, updates.size());
        assertTrue(updates.get(0).spec().command()
                .contains(AuthlibInjectionService.AGENT_PREFIX + API_ROOT));
        assertEquals(Boolean.TRUE, view.get("enabled"));
        assertEquals(Boolean.TRUE, view.get("supported"));
    }

    @Test
    void applyDisableStripsAgentWithoutUpload() {
        instances.save(instance("i1", "exited",
                AuthlibInjectionService.inject(List.of("java", "-jar", "server.jar"), API_ROOT)));

        Map<String, Object> view = service.apply("admin", "user:1", "i1", false);

        assertTrue(uploads.isEmpty());
        assertEquals(1, updates.size());
        assertFalse(AuthlibInjectionService.isInjected(updates.get(0).spec()));
        assertEquals(Boolean.FALSE, view.get("enabled"));
    }

    @Test
    void applyIsNoOpWhenAlreadyInDesiredState() {
        instances.save(instance("i1", "exited", List.of("java", "-jar", "server.jar")));
        settings(true, API_ROOT, JAR_SHA);
        service.apply("admin", "user:1", "i1", false);
        assertTrue(updates.isEmpty());
        assertTrue(uploads.isEmpty());
    }

    @Test
    void applyRejectsWhenGlobalInjectionDisabled() {
        instances.save(instance("i1", "exited", List.of("java", "-jar", "server.jar")));
        settings(false, API_ROOT, null);
        assertThrows(McpanelBusinessException.class, () -> service.apply("admin", "user:1", "i1", true));
        assertTrue(updates.isEmpty());
    }

    @Test
    void applyRejectsSha256Mismatch() {
        instances.save(instance("i1", "exited", List.of("java", "-jar", "server.jar")));
        settings(true, API_ROOT, "deadbeef");
        assertThrows(McpanelBusinessException.class, () -> service.apply("admin", "user:1", "i1", true));
        assertTrue(updates.isEmpty());
        assertTrue(uploads.isEmpty());
    }

    @Test
    void viewReportsUnsupportedWithoutApiRoot() {
        instances.save(instance("i1", "exited", List.of("java", "-jar", "server.jar")));
        settings(true, null, null);
        Map<String, Object> view = service.view("user:1", "i1");
        assertEquals(Boolean.FALSE, view.get("supported"));
        assertEquals(Boolean.FALSE, view.get("enabled"));
        assertTrue(String.valueOf(view.get("reason")).contains("API 根"));
    }
}
