package online.yudream.base.plugin.mcpanel.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 整合包导入：mrpack（Modrinth）与 CurseForge zip。
 * - mrpack：解析 modrinth.index.json，服务端过滤（env.server=unsupported 剔除、optional
 *   默认保留），overrides 遵官方三目录语义，产出节点安装计划（CDN 直连下载 +
 *   overrides 经面板上传通道下发）。
 * - CurseForge：manifest.json（CF 打包标准）只有 projectID/fileID，无 CF API key 时经
 *   cfwidget（公开、无鉴权）解析文件名，再按 forgecdn 公开 CDN 直链构造下载地址
 *   （参考 YMCL 的 edge.forgecdn.net/files/{fid/1000}/{fid%1000}/{name} 规则）；
 *   minecraftinstance.json（CF 客户端导出）自带文件名，直接构造。
 * 解析结果经 {@link ModpackInspectStore} 暂存（token + TTL），创建实例后由
 * modpack-apply 一次性下发安装计划。
 */
public class ModpackService {

    private static final long MAX_ZIP_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 5000;
    private static final java.time.Duration CFWIDGET_TIMEOUT = java.time.Duration.ofSeconds(10);

    private final ObjectMapper mapper = McpanelJson.mapper();
    private final SettingsService settings;
    /** 外呼 User-Agent（YDAP/{插件版本}）。 */
    private final String userAgent;

    private final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    public ModpackService(SettingsService settings, String version) {
        this.settings = settings;
        this.userAgent = "YDAP/" + (version == null || version.isBlank() ? "1.0" : version);
    }

    public record ImportResult(String name, String mcVersion, String loader, String loaderVersion,
                               List<Map<String, Object>> plan, List<Map<String, Object>> overrides,
                               List<String> skippedClientOnly, List<String> coreChain, String resolution) {
    }

    private String loaderOf(JsonNode dependencies) {
        for (String candidate : List.of("fabric-loader", "forge-loader", "neoforge-loader", "quilt-loader")) {
            if (!dependencies.path(candidate).isMissingNode()) {
                return candidate.replace("-loader", "");
            }
        }
        if (!dependencies.path("modloader").isMissingNode()) {
            return "fabric";
        }
        return "";
    }

    /** 服务端无意义、一律不下载的客户端内容路径前缀（资源包/光影包/旧版材质包）。 */
    private static final List<String> CLIENT_ONLY_PATH_PREFIXES =
            List.of("shaderpacks/", "resourcepacks/", "texturepacks/");

    /** 核心候选链（静态）：纯插件包 → [paper, purpur]；加载器包 → [loader]；模糊 → [paper, loader]。 */
    private List<String> coreChainOf(List<Map<String, Object>> plan, String loader) {
        boolean anyModsDir = plan.stream().anyMatch(item -> String.valueOf(item.get("path")).startsWith("mods/"));
        boolean anyPluginsDir = plan.stream().anyMatch(item -> String.valueOf(item.get("path")).startsWith("plugins/"));
        List<String> chain = new ArrayList<>();
        if (anyPluginsDir && !anyModsDir) {
            chain.add("paper");
            chain.add("purpur");
        } else if (anyModsDir && !loader.isBlank()) {
            chain.add(loader);
        } else if (anyModsDir) {
            chain.add("paper");
        } else {
            chain.add("paper");
            if (!loader.isBlank()) {
                chain.add(loader);
            }
        }
        return chain;
    }

    /** Modrinth 有效源列表（镜像优先、官方兜底；旧单镜像设置置顶保留）。 */
    public List<PanelSettings.ResourceSource> modrinthSources() {
        return ResourceSources.effectiveModrinth(settings == null ? null : settings.load().modpack());
    }

    /** CurseForge 有效源列表。 */
    public List<PanelSettings.ResourceSource> curseforgeSources() {
        return ResourceSources.effectiveCurseforge(settings == null ? null : settings.load().modpack());
    }

    /** 计划条目补全源回退列表（urls）；实例安装入口统一走这里，幂等。 */
    public Map<String, Object> enrichPlanItem(Map<String, Object> item) {
        return ResourceSources.enrichPlanItem(item, modrinthSources(), curseforgeSources());
    }

    // ---------- 统一识别入口 ----------

    /** zip 归档内容：命中的索引文件 + overrides（mrpack 三目录 / CF overrides 均收集）。 */
    private record Archive(JsonNode index, String indexName, Map<String, byte[]> overrideFiles) {
    }

    /** 自动识别 mrpack / CurseForge zip；CF manifest 在此同步解析文件名（cfwidget + forgecdn）。 */
    public ImportResult inspect(byte[] zipBytes) {
        Archive archive = readArchive(zipBytes);
        if ("modrinth.index.json".equals(archive.indexName())) {
            return buildMrpack(archive.index(), archive.overrideFiles());
        }
        return buildCurseforge(archive.index(), archive.indexName(), archive.overrideFiles());
    }

    /**
     * 识别结果 → 前端摘要（旧 multipart inspect 与分片上传 commit 共用同一形状，
     * token 供 modpack-apply 取回识别结果）。
     */
    public static Map<String, Object> summarize(String token, String fileName, ImportResult result) {
        String coreKind = result.coreChain().isEmpty() ? "" : result.coreChain().get(0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("token", token);
        summary.put("fileName", fileName);
        summary.put("name", result.name());
        summary.put("mcVersion", result.mcVersion());
        summary.put("loader", result.loader());
        summary.put("loaderVersion", result.loaderVersion());
        summary.put("fileCount", result.plan().size());
        summary.put("overrideCount", result.overrides().size());
        summary.put("skippedCount", result.skippedClientOnly().size());
        summary.put("coreChain", result.coreChain());
        summary.put("resolution", result.resolution());
        summary.put("coreSupported", switch (coreKind) {
            case "paper", "purpur", "fabric", "quilt" -> true;
            default -> false; // forge/neoforge：服务端 installer 需交互执行，暂不支持自动开服
        });
        return summary;
    }

    private Archive readArchive(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0 || zipBytes.length > MAX_ZIP_BYTES) {
            throw McpanelBusinessException.invalid("整合包文件缺失或超过 64MB");
        }
        JsonNode mrpackIndex = null;
        JsonNode cfManifest = null;
        JsonNode cfInstance = null;
        Map<String, byte[]> overrideFiles = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            int entries = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw McpanelBusinessException.invalid("整合包条目过多");
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (name.contains("..")) {
                    throw McpanelBusinessException.invalid("整合包含非法路径");
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                zip.transferTo(buffer);
                if (buffer.size() > 64L * 1024 * 1024) {
                    throw McpanelBusinessException.invalid("整合包单文件过大");
                }
                if (name.equals("modrinth.index.json")) {
                    mrpackIndex = mapper.readTree(buffer.toByteArray());
                }
                else if (name.equals("manifest.json")) {
                    cfManifest = mapper.readTree(buffer.toByteArray());
                }
                else if (name.equals("minecraftinstance.json")) {
                    cfInstance = mapper.readTree(buffer.toByteArray());
                }
                else if (name.startsWith("overrides/") || name.startsWith("server-overrides/")) {
                    overrideFiles.put(name.substring(name.indexOf('/') + 1), buffer.toByteArray());
                }
                else if (name.startsWith("client-overrides/")) {
                    // 客户端专属 overrides：服务端安装跳过（官方语义）。
                }
            }
        } catch (McpanelBusinessException error) {
            throw error;
        } catch (Exception error) {
            throw McpanelBusinessException.invalid("整合包解析失败：" + error.getMessage());
        }
        if (mrpackIndex != null) {
            return new Archive(mrpackIndex, "modrinth.index.json", overrideFiles);
        }
        if (cfManifest != null && cfManifest.has("files")) {
            return new Archive(cfManifest, "manifest.json", overrideFiles);
        }
        if (cfInstance != null && cfInstance.has("installedAddons")) {
            return new Archive(cfInstance, "minecraftinstance.json", overrideFiles);
        }
        throw McpanelBusinessException.invalid(
                "未识别的整合包格式：需要 modrinth.index.json（mrpack）、manifest.json 或 minecraftinstance.json（CurseForge）");
    }

    // ---------- mrpack（索引 → 计划） ----------

    private ImportResult buildMrpack(JsonNode index, Map<String, byte[]> overrideFiles) {
        JsonNode dependencies = index.path("dependencies");
        String mcVersion = dependencies.path("minecraft").asText("");
        String loader = loaderOf(dependencies);
        String loaderVersion = dependencies.path(loader + "-loader").asText(
                dependencies.path("modloader").asText(""));
        List<Map<String, Object>> plan = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (JsonNode file : index.path("files")) {
            JsonNode env = file.path("env");
            String serverSupport = env.path("server").asText("required");
            String path = file.path("path").asText("");
            if (path.contains("..") || path.startsWith("/")) {
                throw McpanelBusinessException.invalid("文件路径越界：" + path);
            }
            // 客户端内容排除：资源包/光影包服务端用不上；仅客户端倾向（client=required
            // 且 server=optional）的 mod 也不下发——服务端缺它照常运行，省整包下载量。
            String normalizedPath = path.replace('\\', '/');
            String lowerPath = normalizedPath.toLowerCase(java.util.Locale.ROOT);
            if (CLIENT_ONLY_PATH_PREFIXES.stream().anyMatch(lowerPath::startsWith)) {
                skipped.add(normalizedPath);
                continue;
            }
            String clientSupport = env.path("client").asText("");
            if ("required".equals(clientSupport) && "optional".equals(serverSupport)) {
                skipped.add(normalizedPath);
                continue;
            }
            if ("unsupported".equals(serverSupport)) {
                skipped.add(path);
                continue;
            }
            String url = file.path("downloads").path(0).asText("");
            if (url.isBlank()) {
                throw McpanelBusinessException.invalid("文件缺少下载地址：" + path);
            }
            List<String> candidates = ResourceSources.cdnCandidates(url, modrinthSources(),
                    "https://cdn.modrinth.com/");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", candidates.get(0));
            if (candidates.size() > 1) {
                item.put("urls", candidates);
            }
            item.put("path", path);
            String sha512 = file.path("hashes").path("sha512").asText("");
            if (!sha512.isBlank()) {
                item.put("sha512", sha512);
            }
            item.put("size", file.path("size").asLong(0));
            plan.add(item);
        }
        return finish(index.path("name").asText("modpack"), mcVersion, loader, loaderVersion,
                plan, overridePlanOf(overrideFiles), skipped, "mrpack");
    }

    // ---------- CurseForge ----------

    /**
     * CF manifest 只有 projectID/fileID：
     * - minecraftinstance.json 自带文件名 → 直接构造 forgecdn 直链；
     * - manifest.json 经 cfwidget（无鉴权公开 API，按 project 去重、缓存、4 并发）解析文件名，
     *   再按 YMCL 同款规则构造 edge.forgecdn.net 直链。
     */
    private ImportResult buildCurseforge(JsonNode index, String indexName, Map<String, byte[]> overrideFiles) {
        String name;
        String mcVersion;
        String loader;
        String loaderVersion;
        List<CfFileRef> files = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if ("manifest.json".equals(indexName)) {
            name = index.path("name").asText("CurseForge 整合包");
            mcVersion = index.path("dependencies").path("minecraft").asText("");
            String modloader = index.path("dependencies").path("modloader").asText("");
            String[] parsed = parseCfModloader(modloader, mcVersion);
            loader = parsed[0];
            loaderVersion = parsed[1];
            for (JsonNode file : index.path("files")) {
                long projectId = file.path("projectID").asLong(0);
                long fileId = file.path("fileID").asLong(0);
                if (!file.path("required").asBoolean(true)) {
                    skipped.add("optional#" + projectId + "/" + fileId);
                    continue;
                }
                if (projectId <= 0 || fileId <= 0) {
                    continue;
                }
                files.add(new CfFileRef(projectId, fileId, null));
            }
        }
        else {
            name = index.path("name").asText("CurseForge 实例");
            mcVersion = index.path("minecraftVersion").asText(index.path("baseModLoader")
                    .path("minecraftVersion").asText(""));
            String modloader = index.path("baseModLoader").path("name").asText("");
            String[] parsed = parseCfModloader(modloader, mcVersion);
            loader = parsed[0];
            loaderVersion = parsed[1];
            for (JsonNode addon : index.path("installedAddons")) {
                long projectId = addon.path("addonID").asLong(0);
                JsonNode installed = addon.path("installedFile");
                long fileId = installed.path("id").asLong(0);
                String fileName = installed.path("filename").asText("");
                if (projectId <= 0 || fileId <= 0) {
                    continue;
                }
                files.add(new CfFileRef(projectId, fileId, fileName));
            }
        }
        if (files.isEmpty()) {
            throw McpanelBusinessException.invalid("整合包没有可安装的服务端文件");
        }
        boolean needResolve = files.stream().anyMatch(file -> file.fileName() == null);
        String resolution = "embedded"; // minecraftinstance.json 自带文件名，零外呼
        Map<String, CfResolution> resolved = Map.of();
        if (needResolve) {
            java.util.Optional<String> apiKey = settings == null
                    ? java.util.Optional.empty() : settings.cfApiKey();
            if (apiKey.isPresent() && apiKey.get().isBlank() == false) {
                // 设置页已配置 key：走官方 API 批量解析（可靠、带官方 downloadUrl）。
                resolution = "official";
                resolved = resolveViaOfficialApi(files, apiKey.get().trim());
            }
            else {
                // 未配 key：cfwidget 公开接口 + forgecdn 直链（参考 YMCL 的无 key 规则）。
                resolution = "cfwidget";
                resolved = resolveViaCfWidget(files);
            }
        }
        List<Map<String, Object>> plan = new ArrayList<>();
        for (CfFileRef file : files) {
            String fileName = file.fileName();
            String url = null;
            CfResolution found = resolved.get(file.projectId() + "#" + file.fileId());
            if (found != null) {
                fileName = found.fileName();
                url = found.url();
            }
            if (fileName == null || fileName.isBlank()) {
                skipped.add("unresolved#" + file.projectId() + "/" + file.fileId());
                continue;
            }
            List<String> candidates = ResourceSources.cdnCandidates(
                    url != null ? url : forgeCdnUrl(file.fileId(), fileName), curseforgeSources(),
                    "https://edge.forgecdn.net/", "https://mediafilez.forgecdn.net/");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", candidates.get(0));
            if (candidates.size() > 1) {
                item.put("urls", candidates);
            }
            item.put("path", "mods/" + fileName);
            plan.add(item);
        }
        if (plan.isEmpty()) {
            throw McpanelBusinessException.invalid("CurseForge 文件解析全部失败"
                    + ("official".equals(resolution) ? "（API key 可能无效，请检查设置页）" : "（cfwidget 可能限流，请稍后重试或配置 CF API key）"));
        }
        return finish(name, mcVersion, loader, loaderVersion, plan, overridePlanOf(overrideFiles), skipped, resolution);
    }

    /** 解析结果：文件名 + 官方直链（cfwidget/embedded 路径无 url，落 forgecdn 构造）。 */
    private record CfResolution(String fileName, String url) {
    }

    /** "forge-1.19.2-43.2.0" / "fabric-loader-0.15.11-1.21.1" → [loader, loaderVersion]（剔除 MC 版本后缀）。 */
    private String[] parseCfModloader(String modloader, String mcVersion) {
        String value = modloader == null ? "" : modloader.trim();
        if (value.isEmpty()) {
            return new String[]{"", ""};
        }
        String[][] prefixes = {
                {"fabric-loader", "fabric"}, {"fabric", "fabric"},
                {"neoforge", "neoforge"},
                {"quilt-loader", "quilt"}, {"quilt", "quilt"},
                {"forge", "forge"},
        };
        String loader = null;
        String version = "";
        for (String[] prefix : prefixes) {
            if (value.startsWith(prefix[0] + "-")) {
                loader = prefix[1];
                version = value.substring(prefix[0].length() + 1);
                break;
            }
        }
        if (loader == null) {
            return new String[]{"", ""};
        }
        if (!mcVersion.isEmpty()) {
            // "forge-{mc}-{ver}" 形态去掉 mc 前缀；"fabric-loader-{ver}-{mc}" 形态去掉 mc 后缀。
            if (version.startsWith(mcVersion + "-")) {
                version = version.substring(mcVersion.length() + 1);
            }
            else {
                int cut = version.lastIndexOf("-" + mcVersion);
                if (cut > 0) {
                    version = version.substring(0, cut);
                }
            }
        }
        return new String[]{loader, version};
    }

    /** CF 条目（fileName 仅 minecraftinstance.json 自带；manifest 需解析）。 */
    record CfFileRef(long projectId, long fileId, String fileName) {
    }

    /**
     * POST /mods/files 按源列表回退：MCIM 代理在前（仅部分端点可达，失败换下一个），官方 API 兜底。
     * 携带管理员配置的 x-api-key 透传；全部源 401/403 才判定 key 无效。
     */
    private JsonNode postFilesViaSources(List<Long> batch, String apiKey) throws Exception {
        String body = mapper.writeValueAsString(Map.of("fileIds", batch));
        Exception lastError = null;
        int lastStatus = 0;
        for (PanelSettings.ResourceSource source : curseforgeSources()) {
            String apiBase = source.apiBase() == null ? "" : source.apiBase().trim();
            if (apiBase.isBlank()) {
                continue;
            }
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(apiBase + "/mods/files"))
                        .timeout(CFWIDGET_TIMEOUT)
                        .header("User-Agent", userAgent)
                        .header("x-api-key", apiKey)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build();
                java.net.http.HttpResponse<String> response = http.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return mapper.readTree(response.body()).path("data");
                }
                lastStatus = response.statusCode();
                lastError = new IllegalStateException("CurseForge API HTTP " + response.statusCode()
                        + "（" + source.name() + "）");
            }
            catch (Exception error) {
                lastError = error;
            }
            // 任何失败（含 401/403：代理可能不透传密钥）都换下一个源，官方兜底定论。
        }
        if (lastStatus == 401 || lastStatus == 403) {
            throw McpanelBusinessException.invalid(
                    "CurseForge API key 无效（HTTP " + lastStatus + "），请检查设置页密钥");
        }
        throw lastError == null
                ? McpanelBusinessException.invalid("CurseForge API 无可用源")
                : new McpanelBusinessException("internal.error", 502,
                        "CurseForge API 全部源失败：" + lastError.getMessage());
    }

    /** 官方 API：POST /v1/mods/files 批量取文件详情（fileName + downloadUrl）；50 个一批。 */
    private Map<String, CfResolution> resolveViaOfficialApi(List<CfFileRef> files, String apiKey) {        List<Long> fileIds = files.stream()
                .filter(file -> file.fileName() == null)
                .map(CfFileRef::fileId)
                .distinct()
                .toList();
        Map<Long, CfResolution> byFileId = new LinkedHashMap<>();
        for (int from = 0; from < fileIds.size(); from += 50) {
            List<Long> batch = fileIds.subList(from, Math.min(from + 50, fileIds.size()));
            JsonNode data;
            try {
                data = postFilesViaSources(batch, apiKey);
            }
            catch (McpanelBusinessException error) {
                throw error;
            }
            catch (Exception error) {
                throw McpanelBusinessException.invalid("CurseForge API 请求失败：" + error.getMessage());
            }
            for (JsonNode item : data) {
                long id = item.path("id").asLong(0);
                String fileName = item.path("fileName").asText("");
                String downloadUrl = item.path("downloadUrl").isTextual() ? item.path("downloadUrl").asText() : null;
                if (id > 0 && !fileName.isBlank()) {
                    byFileId.put(id, new CfResolution(fileName,
                            downloadUrl == null ? forgeCdnUrl(id, fileName) : downloadUrl));
                }
            }
        }
        Map<String, CfResolution> out = new LinkedHashMap<>();
        for (CfFileRef file : files) {
            CfResolution found = byFileId.get(file.fileId());
            if (found != null) {
                out.put(file.projectId() + "#" + file.fileId(), found);
            }
        }
        return out;
    }

    /** cfwidget 按 project 去重解析文件名（4 并发、10s 超时、内存缓存）；返回键 "pid#fid"。 */
    private Map<String, CfResolution> resolveViaCfWidget(List<CfFileRef> files) {
        Map<Long, Map<Long, String>> projectCache = new java.util.concurrent.ConcurrentHashMap<>();
        List<Long> projectIds = files.stream().map(CfFileRef::projectId).distinct().toList();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (Long projectId : projectIds) {
                futures.add(pool.submit(() -> {
                    try {
                        projectCache.put(projectId, fetchCfWidgetFiles(projectId));
                    }
                    catch (RuntimeException ignored) {
                        projectCache.putIfAbsent(projectId, Map.of());
                    }
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get(CFWIDGET_TIMEOUT.toMillis() + 5_000, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
                catch (Exception ignored) {
                    // 单项目失败不阻断整体；未解析条目按 skipped 记录。
                }
            }
        }
        finally {
            pool.shutdownNow();
        }
        Map<String, CfResolution> out = new LinkedHashMap<>();
        for (CfFileRef file : files) {
            if (file.fileName() == null) {
                String fileName = projectCache.getOrDefault(file.projectId(), Map.of()).get(file.fileId());
                if (fileName != null) {
                    out.put(file.projectId() + "#" + file.fileId(), new CfResolution(fileName, null));
                }
            }
        }
        return out;
    }

    private final Map<Long, Map<Long, String>> cfWidgetCache = new java.util.concurrent.ConcurrentHashMap<>();

    private Map<Long, String> fetchCfWidgetFiles(long projectId) throws RuntimeException {
        Map<Long, String> cached = cfWidgetCache.get(projectId);
        if (cached != null) {
            return cached;
        }
        JsonNode body;
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://api.cfwidget.com/" + projectId))
                    .timeout(CFWIDGET_TIMEOUT)
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = http.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw McpanelBusinessException.invalid("cfwidget 限流（HTTP 429），请约一分钟后再试");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("cfwidget HTTP " + response.statusCode());
            }
            body = mapper.readTree(response.body());
        }
        catch (McpanelBusinessException error) {
            throw error;
        }
        catch (Exception error) {
            throw new IllegalStateException("cfwidget 请求失败：" + error.getMessage());
        }
        Map<Long, String> files = new LinkedHashMap<>();
        for (JsonNode file : body.path("files")) {
            long id = file.path("id").asLong(0);
            String fileName = file.path("name").asText(file.path("displayName").asText(""));
            if (id > 0 && !fileName.isBlank()) {
                files.put(id, fileName);
            }
        }
        cfWidgetCache.put(projectId, files);
        return files;
    }

    /** YMCL 同款规则：edge.forgecdn.net/files/{fid/1000}/{fid%1000}/{fileName}（公开 CDN，无需 key）。 */
    static String forgeCdnUrl(long fileId, String fileName) {
        String encoded = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "https://edge.forgecdn.net/files/" + (fileId / 1000) + "/" + (fileId % 1000) + "/" + encoded;
    }

    // ---------- 公共收尾 ----------

    private static List<Map<String, Object>> overridePlanOf(Map<String, byte[]> overrideFiles) {
        List<Map<String, Object>> overridePlan = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : overrideFiles.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.getKey());
            item.put("contentB64", Base64.getEncoder().encodeToString(file.getValue()));
            item.put("size", file.getValue().length);
            overridePlan.add(item);
        }
        return overridePlan;
    }

    private ImportResult finish(String name, String mcVersion, String loader, String loaderVersion,
                                List<Map<String, Object>> plan, List<Map<String, Object>> overrides,
                                List<String> skipped, String resolution) {
        return new ImportResult(name, mcVersion, loader, loaderVersion,
                plan, overrides, skipped, coreChainOf(plan, loader), resolution);
    }
}
