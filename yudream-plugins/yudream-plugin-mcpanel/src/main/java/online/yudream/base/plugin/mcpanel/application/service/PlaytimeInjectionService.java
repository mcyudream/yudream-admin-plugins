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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Map;

/**
 * 在线时长统计注入（实例粒度，制品矩阵版）：按实例核心类型与 MC 版本从面板
 * 设置的制品矩阵（{@link PanelSettings.Playtime}）挑选匹配制品，下载后放入
 * 实例的插件/模组目录（plugin → /plugins，mod → /mods，固定文件名便于开关
 * 幂等）；目录里存在该文件即视为已注入（单一真相源），下次启动生效。
 *
 * <p>与 authlib 注入同语义：运行中实例拒绝切换（409，运行中 JVM 会锁住 jar，
 * Windows 宿主上删除会失败）；开关不改动启动命令，无需节点容器重建。
 */
public class PlaytimeInjectionService {

    /** 注入制品在实例插件/模组目录里的固定文件名（容器内 /data/<dir>/playtime-agent.jar）。 */
    public static final String AGENT_JAR = "playtime-agent.jar";
    /** 外部 URL 拉取上限：与 authlib 注入一致（96MiB）。 */
    private static final long MAX_JAR_BYTES = 96L * 1024 * 1024;
    private static final List<String> MOD_KINDS = List.of("fabric", "forge", "neoforge", "quilt");

    private final McpanelInstanceRepository instances;
    private final SettingsService settingsService;
    private final ArtifactStoreService artifacts;
    private final InstanceFileOps fileOps;
    private final McpanelInstanceAppService.AuditRecorder audit;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 实例文件通道（upload 走实例上传，delete/list 走文件代理；函数式隔离便于测试）。 */
    public interface InstanceFileOps {
        void upload(String scopeKey, String instanceId, String path, byte[] data, String sha256Hex);

        void delete(String scopeKey, String instanceId, String path);

        /** 列目录下的文件名；目录不存在/数据暂缺返回空列表。 */
        List<String> listNames(String scopeKey, String instanceId, String dir);
    }

    public PlaytimeInjectionService(McpanelInstanceRepository instances, SettingsService settingsService,
                                    ArtifactStoreService artifacts, InstanceFileOps fileOps,
                                    McpanelInstanceAppService.AuditRecorder audit) {
        this.instances = instances;
        this.settingsService = settingsService;
        this.artifacts = artifacts;
        this.fileOps = fileOps;
        this.audit = audit;
    }

    /** 注入视图：面板制品矩阵是否可用、当前实例是否已注入、匹配到的制品与目标目录。 */
    public Map<String, Object> view(String scopeKey, String instanceId) {
        McpanelInstance instance = find(instanceId);
        PanelSettings.Playtime settings = settings();
        String dir = targetDir(instance.kind());
        PanelSettings.Artifact matched = settings.enabled()
                ? pick(settings.artifacts(), instance.kind(), instance.mcVersion())
                : null;
        String reason = unsupportedReason(settings, matched);
        boolean enabled = fileExists(scopeKey, instanceId, dir);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported", reason == null);
        result.put("reason", reason);
        result.put("enabled", enabled);
        result.put("running", "running".equalsIgnoreCase(instance.state()));
        result.put("dir", dir);
        result.put("matchedName", matched == null ? "" : String.valueOf(matched.name()));
        return result;
    }

    /**
     * 切换注入：开启 = 按矩阵挑选制品、下载并放入插件/模组目录；关闭 = 删除该
     * 固定名文件。下次启动生效；运行中实例拒绝（Windows 宿主上运行中的 jar 会被锁）。
     */
    public Map<String, Object> apply(String actor, String scopeKey, String instanceId, boolean enable) {
        McpanelInstance instance = find(instanceId);
        if ("running".equalsIgnoreCase(instance.state())) {
            throw new McpanelBusinessException("instance-running", 409, "请先停止实例再修改在线时长注入");
        }
        String dir = targetDir(instance.kind());
        boolean currentlyInjected = fileExists(scopeKey, instanceId, dir);
        if (enable == currentlyInjected) {
            return view(scopeKey, instanceId);
        }
        if (enable) {
            PanelSettings.Playtime settings = settings();
            PanelSettings.Artifact matched = pick(settings.artifacts(), instance.kind(), instance.mcVersion());
            String reason = unsupportedReason(settings, matched);
            if (reason != null) {
                throw McpanelBusinessException.invalid("无法开启在线时长注入：" + reason);
            }
            byte[] jar = loadArtifact(matched);
            fileOps.upload(scopeKey, instanceId, dir + "/" + AGENT_JAR, jar, sha256Hex(jar));
            audit.record(actor, "instance.playtime-inject", "instance", instanceId,
                    "开启在线时长注入（制品 " + matched.name() + " → " + dir + "/" + AGENT_JAR
                            + "；下次启动生效）", instance.tenantId());
        }
        else {
            fileOps.delete(scopeKey, instanceId, dir + "/" + AGENT_JAR);
            audit.record(actor, "instance.playtime-inject", "instance", instanceId,
                    "关闭在线时长注入（删除 " + dir + "/" + AGENT_JAR + "；下次启动生效）",
                    instance.tenantId());
        }
        return view(scopeKey, instanceId);
    }

    // ---------- 制品矩阵匹配 ----------

    /** 实例目录：mod 加载器（fabric/forge/neoforge/quilt）→ mods，其余核心类型 → plugins。 */
    public static String targetDir(String kind) {
        return "mod".equals(formOf(kind)) ? "mods" : "plugins";
    }

    /** 制品形态：mod 加载器实例为 mod，其余（paper/folia/velocity 等）为 plugin。 */
    public static String formOf(String kind) {
        String k = kind == null ? "" : kind.toLowerCase(Locale.ROOT).trim();
        return MOD_KINDS.contains(k) ? "mod" : "plugin";
    }

    /**
     * 从制品矩阵挑选首个匹配实例形态（plugin/mod + 加载器）与 MC 版本区间的制品；
     * mcMin/mcMax 空 = 不限。无匹配返回 null。
     */
    public static PanelSettings.Artifact pick(List<PanelSettings.Artifact> artifacts,
                                              String instanceKind, String mcVersion) {
        String form = formOf(instanceKind);
        String loader = form.equals("mod") ? loaderOf(instanceKind) : null;
        for (PanelSettings.Artifact artifact : artifacts == null ? List.<PanelSettings.Artifact>of() : artifacts) {
            if (artifact == null || !form.equals(artifact.kind())) {
                continue;
            }
            if (loader != null && artifact.loaders() != null && !artifact.loaders().isEmpty()
                    && !artifact.loaders().stream().anyMatch(l -> loader.equalsIgnoreCase(
                            l == null ? "" : l.toLowerCase(Locale.ROOT).trim()))) {
                continue;
            }
            if (!versionInRange(mcVersion, artifact.mcMin(), artifact.mcMax())) {
                continue;
            }
            return artifact;
        }
        return null;
    }

    private static String loaderOf(String kind) {
        return kind == null ? "" : kind.toLowerCase(Locale.ROOT).trim();
    }

    /** MC 版本是否落在 [mcMin, mcMax] 内（含边界；空 = 该侧不限；实例版本空 = 仅双侧不限时匹配）。 */
    static boolean versionInRange(String version, String mcMin, String mcMax) {
        boolean hasMin = mcMin != null && !mcMin.isBlank();
        boolean hasMax = mcMax != null && !mcMax.isBlank();
        if (version == null || version.isBlank()) {
            return !hasMin && !hasMax;
        }
        if (hasMin && compareMcVersions(version, mcMin) < 0) {
            return false;
        }
        return !hasMax || compareMcVersions(version, mcMax) <= 0;
    }

    /** MC 版本比较：按 "." 分段数值逐段比较，缺段补 0（1.21 < 1.21.4）；非数字段取前导数字。 */
    static int compareMcVersions(String left, String right) {
        long[] a = segments(left);
        long[] b = segments(right);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            long x = i < a.length ? a[i] : 0;
            long y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static long[] segments(String version) {
        String[] parts = (version == null ? "" : version.trim().toLowerCase(Locale.ROOT)).split("\\.");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int end = 0;
            while (end < part.length() && Character.isDigit(part.charAt(end))) {
                end++;
            }
            out[i] = end == 0 ? 0 : Long.parseLong(part.substring(0, end));
        }
        return out;
    }

    // ---------- 状态与注入源 ----------

    private McpanelInstance find(String instanceId) {
        return instances.findById(instanceId)
                .orElseThrow(() -> McpanelBusinessException.notFound("实例不存在"));
    }

    private PanelSettings.Playtime settings() {
        PanelSettings settings = settingsService.load();
        return settings == null || settings.playtime() == null
                ? new PanelSettings.Playtime(false, List.of())
                : settings.playtime();
    }

    private String unsupportedReason(PanelSettings.Playtime settings, PanelSettings.Artifact matched) {
        if (!settings.enabled()) {
            return "面板设置未启用在线时长注入";
        }
        if (settings.artifacts() == null || settings.artifacts().isEmpty()) {
            return "面板制品矩阵为空（在面板设置添加制品条目）";
        }
        if (matched == null) {
            return "制品矩阵中没有匹配该实例核心类型与 MC 版本的制品";
        }
        return null;
    }

    private boolean fileExists(String scopeKey, String instanceId, String dir) {
        try {
            return fileOps.listNames(scopeKey, instanceId, dir).contains(AGENT_JAR);
        }
        catch (RuntimeException error) {
            // 实例数据尚未创建等场景：按未注入处理
            return false;
        }
    }

    /** 读取制品：fileId（平台制品库）优先，其次外部 URL；带期望 sha256 校验。 */
    private byte[] loadArtifact(PanelSettings.Artifact artifact) {
        byte[] jar;
        if (artifact.fileId() != null && !artifact.fileId().isBlank()) {
            jar = artifacts.download(artifact.fileId());
        }
        else {
            jar = httpGet(artifact.url());
        }
        if (jar == null || jar.length == 0) {
            throw McpanelBusinessException.invalid("在线时长制品内容为空");
        }
        if (jar.length > MAX_JAR_BYTES) {
            throw McpanelBusinessException.invalid("在线时长制品超过 96MiB 上限");
        }
        String expected = artifact.sha256();
        if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(sha256Hex(jar))) {
            throw McpanelBusinessException.invalid("在线时长制品校验失败：sha256 与面板设置登记值不一致");
        }
        return jar;
    }

    private byte[] httpGet(String url) {
        if (url == null || url.isBlank()) {
            throw McpanelBusinessException.invalid("在线时长制品下载地址为空");
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
                        "在线时长制品下载失败：HTTP " + response.statusCode());
            }
            return response.body();
        }
        catch (IOException error) {
            throw McpanelBusinessException.invalid("在线时长制品下载失败：" + error.getMessage());
        }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw McpanelBusinessException.invalid("在线时长制品下载被中断");
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
