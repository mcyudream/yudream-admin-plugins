package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * authlib-injector 注入（实例粒度）：把 -javaagent:authlib-injector.jar=&lt;apiRoot&gt;
 * 追加进实例启动命令，并把注入 jar 下发到实例根目录。面板设置只提供注入源
 * （jar 制品/URL + apiRoot），是否注入由每个实例的开关决定——注入状态即启动命令
 * 本身（单一真相源），关闭开关移除参数，无需额外状态字段。
 *
 * <p>与「改配置需停机」一致：运行中实例拒绝切换（409），停止后开/关，下次启动生效。
 */
public class AuthlibInjectionService {

    /** 注入 jar 在实例根目录的固定文件名（容器内 /data/authlib-injector.jar）。 */
    public static final String AGENT_JAR = "authlib-injector.jar";
    /** javaagent 参数前缀；以本前缀开头即视为已注入（apiRoot 变化时替换重写）。 */
    public static final String AGENT_PREFIX = "-javaagent:" + AGENT_JAR + "=";
    /** 外部 URL 拉取上限：与实例上传通道一致（96MiB）。 */
    private static final long MAX_JAR_BYTES = 96L * 1024 * 1024;

    private final McpanelInstanceRepository instances;
    private final SettingsService settingsService;
    private final ArtifactStoreService artifacts;
    private final AuthlibLinkService authlibLink;
    private final InstanceMutations mutations;
    private final McpanelInstanceAppService.AuditRecorder audit;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 实例变更通道（upload/update 经 McpanelInstanceAppService，函数式隔离便于测试）。 */
    public interface InstanceMutations {
        void upload(String scopeKey, String instanceId, String path, byte[] data, String sha256Hex);

        Map<String, Object> update(String actor, String scopeKey, String instanceId, McpanelInstance spec);
    }

    public AuthlibInjectionService(McpanelInstanceRepository instances, SettingsService settingsService,
                                   ArtifactStoreService artifacts, AuthlibLinkService authlibLink,
                                   InstanceMutations mutations,
                                   McpanelInstanceAppService.AuditRecorder audit) {
        this.instances = instances;
        this.settingsService = settingsService;
        this.artifacts = artifacts;
        this.authlibLink = authlibLink;
        this.mutations = mutations;
        this.audit = audit;
    }

    /** 注入能力视图：面板设置是否齐备、当前实例是否已注入、生效 apiRoot 与不可用原因。 */
    public Map<String, Object> view(String scopeKey, String instanceId) {
        McpanelInstance instance = find(instanceId);
        PanelSettings.Authlib settings = settings();
        String apiRoot = effectiveApiRoot(settings);
        String reason = unsupportedReason(settings, apiRoot);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported", reason == null);
        result.put("reason", reason);
        result.put("enabled", isInjected(instance));
        result.put("running", "running".equalsIgnoreCase(instance.state()));
        result.put("apiRoot", apiRoot);
        return result;
    }

    /**
     * 切换注入：开启 = 下发注入 jar 到实例根目录 + 启动命令追加 -javaagent；
     * 关闭 = 启动命令移除 -javaagent（jar 文件保留，重复开启不重复下载）。
     * 运行中实例拒绝（与改配置同语义），经 update 走节点容器重建。
     */
    public Map<String, Object> apply(String actor, String scopeKey, String instanceId, boolean enable) {
        McpanelInstance instance = find(instanceId);
        if ("running".equalsIgnoreCase(instance.state())) {
            throw new McpanelBusinessException("instance-running", 409, "请先停止实例再修改 authlib 注入");
        }
        boolean currentlyInjected = isInjected(instance);
        if (enable == currentlyInjected) {
            return view(scopeKey, instanceId);
        }
        List<String> newCommand;
        if (enable) {
            PanelSettings.Authlib settings = settings();
            String apiRoot = effectiveApiRoot(settings);
            String reason = unsupportedReason(settings, apiRoot);
            if (reason != null) {
                throw McpanelBusinessException.invalid("无法开启注入：" + reason);
            }
            byte[] jar = loadJar(settings);
            mutations.upload(scopeKey, instanceId, AGENT_JAR, jar, sha256Hex(jar));
            newCommand = inject(instance.command(), apiRoot);
        }
        else {
            newCommand = strip(instance.command());
        }
        mutations.update(actor, scopeKey, instanceId,
                instance.withCommand(newCommand, System.currentTimeMillis()));
        audit.record(actor, "instance.authlib-inject", "instance", instanceId,
                enable ? "开启注入（下次启动生效）" : "关闭注入（下次启动生效）", instance.tenantId());
        return view(scopeKey, instanceId);
    }

    // ---------- 命令改写 ----------

    /** 启动命令是否已注入（任一参数以 agent 前缀开头）。 */
    public static boolean isInjected(McpanelInstance instance) {
        List<String> command = instance.command();
        if (command == null || command.isEmpty()) {
            return false;
        }
        return command.stream().anyMatch(arg -> arg != null && arg.startsWith(AGENT_PREFIX));
    }

    /** 追加 -javaagent（先移除旧注入参数，apiRoot 变化时幂等重写）；非 java 启动命令报错。 */
    public static List<String> inject(List<String> command, String apiRoot) {
        if (apiRoot == null || apiRoot.isBlank()) {
            throw McpanelBusinessException.invalid("验证服 API 根为空");
        }
        List<String> base = strip(command);
        if (base.isEmpty()) {
            throw McpanelBusinessException.invalid("启动命令为空，无法注入");
        }
        String head = base.get(0);
        String binary = head;
        int slash = Math.max(head.lastIndexOf('/'), head.lastIndexOf('\\'));
        if (slash >= 0) {
            binary = head.substring(slash + 1);
        }
        if (!binary.equals("java") && !binary.startsWith("java")) {
            throw McpanelBusinessException.invalid(
                    "启动命令不是 java 启动，无法自动注入 authlib-injector；请改用 java 启动命令或手动添加 -javaagent");
        }
        List<String> result = new ArrayList<>(base);
        result.add(1, AGENT_PREFIX + apiRoot.trim());
        return List.copyOf(result);
    }

    /** 移除全部注入参数（幂等）。 */
    public static List<String> strip(List<String> command) {
        List<String> result = new ArrayList<>();
        for (String arg : sanitized(command)) {
            if (!arg.startsWith(AGENT_PREFIX)) {
                result.add(arg);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> sanitized(List<String> command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        return command.stream().filter(arg -> arg != null && !arg.isBlank()).toList();
    }

    // ---------- 注入源 ----------

    private McpanelInstance find(String instanceId) {
        return instances.findById(instanceId)
                .orElseThrow(() -> McpanelBusinessException.notFound("实例不存在"));
    }

    private PanelSettings.Authlib settings() {
        PanelSettings settings = settingsService.load();
        return settings == null || settings.authlib() == null
                ? new PanelSettings.Authlib(false, null, null, null, null)
                : settings.authlib();
    }

    /** 生效 apiRoot：面板手填优先，否则 authlib-injector 插件联动自动值。 */
    private String effectiveApiRoot(PanelSettings.Authlib settings) {
        if (settings.apiRoot() != null && !settings.apiRoot().isBlank()) {
            return settings.apiRoot().trim();
        }
        try {
            return authlibLink.apiRoot().orElse(null);
        }
        catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    private String unsupportedReason(PanelSettings.Authlib settings, String apiRoot) {
        if (!settings.enabled()) {
            return "面板设置未启用 authlib 注入";
        }
        boolean hasJar = (settings.jarFileId() != null && !settings.jarFileId().isBlank())
                || (settings.jarUrl() != null && !settings.jarUrl().isBlank());
        if (!hasJar) {
            return "未配置注入 jar（在面板设置上传制品或填写下载地址）";
        }
        if (apiRoot == null || apiRoot.isBlank()) {
            return "缺少验证服 API 根（面板设置手填或安装并启用 authlib-injector 插件自动获取）";
        }
        return null;
    }

    /** 读取注入 jar：面板制品库 fileId 优先，其次外部 URL；带期望 sha256 校验。 */
    private byte[] loadJar(PanelSettings.Authlib settings) {
        byte[] jar;
        if (settings.jarFileId() != null && !settings.jarFileId().isBlank()) {
            jar = artifacts.download(settings.jarFileId());
        }
        else {
            jar = httpGet(settings.jarUrl());
        }
        if (jar == null || jar.length == 0) {
            throw McpanelBusinessException.invalid("注入 jar 内容为空");
        }
        if (jar.length > MAX_JAR_BYTES) {
            throw McpanelBusinessException.invalid("注入 jar 超过 96MiB 上限");
        }
        String expected = settings.sha256();
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(sha256Hex(jar))) {
            throw McpanelBusinessException.invalid("注入 jar 校验失败：sha256 与面板设置登记值不一致");
        }
        return jar;
    }

    private byte[] httpGet(String url) {
        if (url == null || url.isBlank()) {
            throw McpanelBusinessException.invalid("注入 jar 下载地址为空");
        }
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(url.trim()))
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", "YuDream-McPanel")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw McpanelBusinessException.invalid(
                        "注入 jar 下载失败：HTTP " + response.statusCode());
            }
            return response.body();
        }
        catch (IOException error) {
            throw McpanelBusinessException.invalid("注入 jar 下载失败：" + error.getMessage());
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw McpanelBusinessException.invalid("注入 jar 下载被中断");
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
