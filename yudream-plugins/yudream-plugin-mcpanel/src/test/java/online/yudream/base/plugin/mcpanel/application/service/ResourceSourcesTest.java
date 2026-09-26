package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MR/CF 换源：有效源列表合成与下载候选展开（纯函数）。 */
class ResourceSourcesTest {

    private static final PanelSettings.ResourceSource MIRROR =
            new PanelSettings.ResourceSource("MCIM 镜像", "https://mod.mcimirror.top/modrinth/v2", "https://mod.mcimirror.top");
    private static final PanelSettings.ResourceSource OFFICIAL =
            new PanelSettings.ResourceSource("官方源", "https://api.modrinth.com/v2", "https://cdn.modrinth.com");

    @Test
    void effectiveModrinthDefaultsOnNull() {
        List<PanelSettings.ResourceSource> sources = ResourceSources.effectiveModrinth(null);
        assertEquals(2, sources.size());
        assertEquals("MCIM 镜像", sources.get(0).name());
        assertEquals("https://cdn.modrinth.com", sources.get(1).cdnBase());
    }

    @Test
    void legacyMirrorBecomesTopPrioritySource() {
        PanelSettings.Modpack modpack = new PanelSettings.Modpack("https://my-mirror.example/modrinth",
                false, null, null);
        List<PanelSettings.ResourceSource> sources = ResourceSources.effectiveModrinth(modpack);
        assertEquals(3, sources.size());
        assertEquals("https://my-mirror.example/modrinth", sources.get(0).cdnBase());
        assertEquals("自定义镜像", sources.get(0).name());
        // 同一 cdnBase 不重复出现
        assertTrue(sources.stream().map(PanelSettings.ResourceSource::cdnBase).distinct().count() == sources.size());
    }

    @Test
    void modrinthCandidatesMirrorFirstOfficialLast() {
        List<String> candidates = ResourceSources.cdnCandidates(
                "https://cdn.modrinth.com/data/AANobbMI/versions/xyz/sodium.jar",
                List.of(MIRROR, OFFICIAL), "https://cdn.modrinth.com/");
        assertEquals(List.of(
                "https://mod.mcimirror.top/data/AANobbMI/versions/xyz/sodium.jar",
                "https://cdn.modrinth.com/data/AANobbMI/versions/xyz/sodium.jar"), candidates);
    }

    @Test
    void unknownHostKeepsSingleCandidate() {
        List<String> candidates = ResourceSources.cdnCandidates(
                "https://github.com/someone/repo/releases/download/v1/x.jar",
                List.of(MIRROR, OFFICIAL), "https://cdn.modrinth.com/");
        assertEquals(List.of("https://github.com/someone/repo/releases/download/v1/x.jar"), candidates);
    }

    @Test
    void curseforgeCandidatesCoverMediafilezWithOriginalFallback() {
        List<PanelSettings.ResourceSource> sources = List.of(
                new PanelSettings.ResourceSource("MCIM 镜像", "https://mod.mcimirror.top/curseforge/v1", "https://mod.mcimirror.top"),
                new PanelSettings.ResourceSource("官方源", "https://api.curseforge.com/v1", "https://edge.forgecdn.net"));
        List<String> candidates = ResourceSources.cdnCandidates(
                "https://mediafilez.forgecdn.net/files/2441/550/sodium.jar",
                sources, "https://edge.forgecdn.net/", "https://mediafilez.forgecdn.net/");
        assertEquals(List.of(
                "https://mod.mcimirror.top/files/2441/550/sodium.jar",
                "https://edge.forgecdn.net/files/2441/550/sodium.jar",
                "https://mediafilez.forgecdn.net/files/2441/550/sodium.jar"), candidates);
    }

    @Test
    void enrichAddsUrlsAndRewritesPrimary() {
        List<PanelSettings.ResourceSource> modrinth = List.of(MIRROR, OFFICIAL);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("url", "https://cdn.modrinth.com/data/A/versions/v/a.jar");
        item.put("path", "mods/a.jar");

        Map<String, Object> enriched = ResourceSources.enrichPlanItem(item, modrinth, List.of());

        assertEquals("https://mod.mcimirror.top/data/A/versions/v/a.jar", enriched.get("url"));
        assertEquals(List.of(
                "https://mod.mcimirror.top/data/A/versions/v/a.jar",
                "https://cdn.modrinth.com/data/A/versions/v/a.jar"), enriched.get("urls"));
        assertEquals("mods/a.jar", enriched.get("path"));
    }

    @Test
    void enrichIsIdempotentAndLeavesUnknownHost() {
        List<PanelSettings.ResourceSource> modrinth = List.of(MIRROR, OFFICIAL);
        Map<String, Object> already = new LinkedHashMap<>();
        already.put("url", "https://cdn.modrinth.com/data/A/versions/v/a.jar");
        already.put("urls", List.of("https://my-cdn.example/a.jar"));
        assertTrue(already == ResourceSources.enrichPlanItem(already, modrinth, List.of()));

        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("url", "https://example.com/x.jar");
        Map<String, Object> out = ResourceSources.enrichPlanItem(unknown, modrinth, List.of());
        assertNull(out.get("urls"));
        assertEquals("https://example.com/x.jar", out.get("url"));
        assertFalse(out.containsKey("urls"));
    }
}
