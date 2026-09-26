package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modrinth / CurseForge 换源解析（纯函数，供 ModrinthService 与 ModpackService 共用）：
 * 源列表按顺序尝试、失败回退下一个；文件直链把官方 CDN 主机替换为各源的 cdnBase，
 * 路径保持原样（MCIM 为透明代理：API = mod.mcimirror.top/modrinth，文件 = 同主机原路径）。
 * 旧版单镜像设置（Modpack.mirror）非空时作为最高优先源保留。
 */
public final class ResourceSources {

    /** 老版本 mcimirror 单镜像（mcimirror.top）与新 mod.mcimirror.top 不同主机，识别后不再重复注入。 */
    private static final String LEGACY_MIRROR_HOST = "mcimirror.top";

    private ResourceSources() {
    }

    /** Modrinth 有效源列表：设置值为准；缺省用内置默认；旧 mirror 设置非空时置顶。 */
    public static List<PanelSettings.ResourceSource> effectiveModrinth(PanelSettings.Modpack modpack) {
        List<PanelSettings.ResourceSource> sources = modpack == null ? null : modpack.modrinthSources();
        List<PanelSettings.ResourceSource> effective = normalized(sources, PanelSettings.defaultModrinthSources());
        String legacy = modpack == null ? "" : String.valueOf(modpack.mirror()).trim();
        if (!legacy.isBlank()) {
            List<PanelSettings.ResourceSource> withLegacy = new ArrayList<>();
            withLegacy.add(new PanelSettings.ResourceSource("自定义镜像", "", legacy));
            for (PanelSettings.ResourceSource source : effective) {
                if (!legacy.equals(source.cdnBase())) {
                    withLegacy.add(source);
                }
            }
            return List.copyOf(withLegacy);
        }
        return effective;
    }

    /** CurseForge 有效源列表：设置值为准；缺省用内置默认。 */
    public static List<PanelSettings.ResourceSource> effectiveCurseforge(PanelSettings.Modpack modpack) {
        List<PanelSettings.ResourceSource> sources = modpack == null ? null : modpack.curseforgeSources();
        return normalized(sources, PanelSettings.defaultCurseforgeSources());
    }

    /**
     * 文件直链候选：url 命中任一官方主机前缀时，按源列表生成各源等价 URL（保序去重），
     * 原始 URL 追加末位兜底；未命中（任意第三方直链）时只返回原 URL。
     */
    public static List<String> cdnCandidates(String url, List<PanelSettings.ResourceSource> sources,
                                             String... officialHostPrefixes) {
        String value = url == null ? "" : url.trim();
        if (value.isBlank()) {
            return List.of();
        }
        String matchedPrefix = null;
        for (String prefix : officialHostPrefixes) {
            if (value.startsWith(prefix)) {
                matchedPrefix = prefix;
                break;
            }
        }
        if (matchedPrefix == null) {
            return List.of(value);
        }
        String suffix = value.substring(matchedPrefix.length());
        Set<String> unique = new LinkedHashSet<>();
        for (PanelSettings.ResourceSource source : sources) {
            String base = source == null ? "" : String.valueOf(source.cdnBase()).trim();
            if (!base.isBlank()) {
                // base 去尾斜杠后补一条：官方前缀含尾斜杠，suffix 无前导斜杠；
                // 兼容 cdnBase 带路径（如 https://x/modrinth）与带/不带尾斜杠的写法。
                unique.add(stripTrailingSlashes(base) + "/" + suffix);
            }
        }
        unique.add(value);
        return List.copyOf(unique);
    }

    private static String stripTrailingSlashes(String base) {
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '/') {
            end--;
        }
        return base.substring(0, end);
    }

    /** 计划条目补全源回退：urls 已存在（幂等）或未命中已知官方主机时原样返回。 */
    public static Map<String, Object> enrichPlanItem(Map<String, Object> item,
                                                     List<PanelSettings.ResourceSource> modrinth,
                                                     List<PanelSettings.ResourceSource> curseforge) {
        if (item == null) {
            return item;
        }
        Object existing = item.get("urls");
        if (existing instanceof List<?> list && !list.isEmpty()) {
            return item;
        }
        String url = String.valueOf(item.getOrDefault("url", ""));
        List<String> candidates;
        if (url.startsWith("https://cdn.modrinth.com/")) {
            candidates = cdnCandidates(url, modrinth, "https://cdn.modrinth.com/");
        }
        else if (url.startsWith("https://edge.forgecdn.net/")
                || url.startsWith("https://mediafilez.forgecdn.net/")) {
            candidates = cdnCandidates(url, curseforge,
                    "https://edge.forgecdn.net/", "https://mediafilez.forgecdn.net/");
        }
        else {
            candidates = List.of(url);
        }
        if (candidates.size() > 1) {
            Map<String, Object> enriched = new LinkedHashMap<>(item);
            enriched.put("urls", candidates);
            enriched.put("url", candidates.get(0));
            return enriched;
        }
        if (!candidates.isEmpty() && !url.equals(candidates.get(0))) {
            Map<String, Object> enriched = new LinkedHashMap<>(item);
            enriched.put("url", candidates.get(0));
            return enriched;
        }
        return item;
    }

    private static List<PanelSettings.ResourceSource> normalized(List<PanelSettings.ResourceSource> sources,
                                                                 List<PanelSettings.ResourceSource> defaults) {
        if (sources == null || sources.isEmpty()) {
            return defaults;
        }
        // 条目允许缺 cdnBase（仅 API 用途），但至少要有一项非空字段，全空条目剔除。
        List<PanelSettings.ResourceSource> valid = sources.stream()
                .filter(source -> source != null
                        && (notBlank(source.apiBase()) || notBlank(source.cdnBase())))
                .toList();
        return valid.isEmpty() ? defaults : List.copyOf(valid);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
