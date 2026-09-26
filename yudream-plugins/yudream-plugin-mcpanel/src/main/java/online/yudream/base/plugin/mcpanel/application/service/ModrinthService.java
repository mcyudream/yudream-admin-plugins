package online.yudream.base.plugin.mcpanel.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modrinth 模组/插件检索与安装计划（对标 MCSM ModManager）。
 * 搜索/版本解析按源列表回退（默认 MCIM 镜像优先、官方兜底）；下载地址展开为
 * 各源等价直链候选，由节点 install.run 按列表回退下载（旧节点忽略候选只取主 URL）。
 * 外呼 User-Agent：YDAP/{插件版本}。
 */
public class ModrinthService {

    private final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final SettingsService settings;
    private final String userAgent;

    public ModrinthService(SettingsService settings, String version) {
        this.settings = settings;
        this.userAgent = "YDAP/" + (version == null || version.isBlank() ? "1.0" : version);
    }

    public Map<String, Object> search(String query, String projectType, int page, int size) {
        String type = projectType == null || projectType.isBlank() ? "mod" : projectType.trim();
        if (!"mod".equals(type) && !"plugin".equals(type) && !"modpack".equals(type)) {
            throw McpanelBusinessException.invalid("类型仅支持 mod / plugin / modpack");
        }
        String q = query == null ? "" : query.trim();
        int limit = Math.min(Math.max(size, 1), 20);
        int offset = Math.max(0, (Math.max(page, 1) - 1) * limit);
        String facets = URLEncoder.encode("[[\"project_type:" + type + "\"]]", StandardCharsets.UTF_8);
        String path = "/search?query=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&facets=" + facets + "&limit=" + limit + "&offset=" + offset
                + "&index=relevance";
        try {
            JsonNode body = getViaSources(path);
            List<Map<String, Object>> records = new ArrayList<>();
            for (JsonNode hit : body.path("hits")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", hit.path("project_id").asText(hit.path("slug").asText("")));
                row.put("slug", hit.path("slug").asText(""));
                row.put("title", hit.path("title").asText(hit.path("slug").asText("")));
                row.put("description", hit.path("description").asText(""));
                row.put("downloads", hit.path("downloads").asLong(0));
                row.put("author", hit.path("author").asText(""));
                row.put("icon", hit.path("icon_url").asText(""));
                row.put("projectType", type);
                List<String> versions = new ArrayList<>();
                if (hit.path("versions").isArray()) {
                    hit.path("versions").forEach(item -> versions.add(item.asText()));
                }
                row.put("gameVersions", versions);
                records.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("records", records);
            result.put("total", body.path("total_hits").asLong(records.size()));
            result.put("page", Math.max(page, 1));
            result.put("size", limit);
            return result;
        } catch (McpanelBusinessException error) {
            throw error;
        } catch (Exception error) {
            throw McpanelBusinessException.invalid("Modrinth 搜索失败：" + error.getMessage());
        }
    }

    /** 取项目最近稳定版本文件，生成 install.run 计划（含源回退候选）。 */
    public Map<String, Object> resolveInstall(String projectId, String gameVersion, String loader,
                                              String projectType, String targetDir) {
        try {
            JsonNode versions = getViaSources("/project/"
                    + URLEncoder.encode(projectId, StandardCharsets.UTF_8) + "/version");
            String type = projectType == null || projectType.isBlank() ? "mod" : projectType;
            String dir = targetDir == null || targetDir.isBlank()
                    ? ("plugin".equals(type) ? "plugins" : "mods")
                    : targetDir.trim();
            JsonNode chosen = null;
            for (JsonNode version : versions) {
                if (!"release".equalsIgnoreCase(version.path("version_type").asText(""))) {
                    continue;
                }
                if (gameVersion != null && !gameVersion.isBlank()) {
                    boolean match = false;
                    for (JsonNode gv : version.path("game_versions")) {
                        if (gameVersion.equals(gv.asText())) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                }
                if (loader != null && !loader.isBlank()) {
                    boolean match = false;
                    for (JsonNode ld : version.path("loaders")) {
                        if (loader.equalsIgnoreCase(ld.asText())) {
                            match = true;
                            break;
                        }
                    }
                    if (!match && "plugin".equals(type)) {
                        // 插件侧 loader 不一定声明，允许
                        match = true;
                    }
                    if (!match) {
                        continue;
                    }
                }
                chosen = version;
                break;
            }
            if (chosen == null && versions.isArray() && !versions.isEmpty()) {
                chosen = versions.get(0);
            }
            if (chosen == null) {
                throw McpanelBusinessException.notFound("未找到可安装版本");
            }
            JsonNode files = chosen.path("files");
            if (!files.isArray() || files.isEmpty()) {
                throw McpanelBusinessException.notFound("版本无文件");
            }
            JsonNode file = files.get(0);
            for (JsonNode item : files) {
                if (item.path("primary").asBoolean(false)) {
                    file = item;
                    break;
                }
            }
            List<String> candidates = ResourceSources.cdnCandidates(file.path("url").asText(""),
                    modrinthSources(), "https://cdn.modrinth.com/");
            if (candidates.isEmpty()) {
                throw McpanelBusinessException.notFound("版本文件缺少下载地址");
            }
            String filename = file.path("filename").asText("download.jar");
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("projectId", projectId);
            plan.put("versionId", chosen.path("id").asText(""));
            plan.put("versionNumber", chosen.path("version_number").asText(""));
            plan.put("url", candidates.get(0));
            if (candidates.size() > 1) {
                plan.put("urls", candidates);
            }
            plan.put("fileName", filename);
            plan.put("targetDir", dir);
            plan.put("path", dir + "/" + filename);
            List<Map<String, Object>> filesPlan = new ArrayList<>();
            Map<String, Object> fileEntry = new LinkedHashMap<>();
            fileEntry.put("url", candidates.get(0));
            if (candidates.size() > 1) {
                fileEntry.put("urls", candidates);
            }
            fileEntry.put("path", dir + "/" + filename);
            filesPlan.add(fileEntry);
            plan.put("files", filesPlan);
            return plan;
        } catch (McpanelBusinessException error) {
            throw error;
        } catch (Exception error) {
            throw McpanelBusinessException.invalid("解析 Modrinth 版本失败：" + error.getMessage());
        }
    }

    /** 有效 Modrinth 源列表（与 ModpackService 同口径：镜像优先、官方兜底、旧 mirror 置顶）。 */
    private List<PanelSettings.ResourceSource> modrinthSources() {
        return ResourceSources.effectiveModrinth(settings == null ? null : settings.load().modpack());
    }

    /** API 调用按源列表回退：MCIM 代理在前，官方兜底；全部失败抛最后一个错误。 */
    private JsonNode getViaSources(String pathAndQuery) throws Exception {
        Exception lastError = null;
        int attempted = 0;
        for (PanelSettings.ResourceSource source : modrinthSources()) {
            String apiBase = source.apiBase() == null ? "" : source.apiBase().trim();
            if (apiBase.isBlank()) {
                continue; // 仅文件直链用途的源不参与 API 调用
            }
            attempted++;
            try {
                return get(apiBase + pathAndQuery);
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (attempted == 0) {
            // 兜底：源列表异常时直连官方，保证功能可用
            return get("https://api.modrinth.com/v2" + pathAndQuery);
        }
        throw lastError == null
                ? McpanelBusinessException.invalid("Modrinth 无可用源")
                : lastError;
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw McpanelBusinessException.invalid("Modrinth 返回 HTTP " + response.statusCode());
        }
        return McpanelJson.mapper().readTree(response.body());
    }
}
