package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 核心下载解析：Fabric/Quilt loader 版本取清单指定或 meta 最新稳定，加载器核心不经镜像源。 */
class CoreDownloadServiceTest {

    /** 按 URL 返回固定 JSON 的假抓取器：未命中的 URL 返回 null（视为 404/不可达）。 */
    private static CoreDownloadService service(Map<String, String> responses, String mirrorBase) {
        return new CoreDownloadService(() -> mirrorBase, url -> {
            String body = responses.get(url);
            return body == null ? null : McpanelJson.mapper().readTree(body);
        });
    }

    @Test
    void fabricUsesModpackDeclaredLoader() {
        // meta 全不可达：installer 走兜底 1.0.3，loader 用整合包清单指定值
        CoreDownloadService.CoreDownloadPlan plan =
                service(Map.of(), null).resolve("fabric", "1.20.1", "0.18.2");
        assertEquals("https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.18.2/1.0.3/server/jar", plan.url());
        assertEquals("official:fabric", plan.source());
    }

    @Test
    void fabricDefaultsToLatestStableLoader() {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://meta.fabricmc.net/v2/versions/loader",
                "[{\"version\":\"0.19.0\",\"stable\":false},{\"version\":\"0.18.2\",\"stable\":true}]");
        responses.put("https://meta.fabricmc.net/v2/versions/installer",
                "[{\"version\":\"1.1.0\",\"stable\":true}]");
        CoreDownloadService.CoreDownloadPlan plan =
                service(responses, null).resolve("fabric", "1.20.1", null);
        assertEquals("https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.18.2/1.1.0/server/jar", plan.url());
    }

    @Test
    void fabricWithoutMetaAndWithoutDeclaredVersionFails() {
        // 不允许悄悄装过期 loader：meta 不可达且无清单版本时显式失败
        assertThrows(McpanelBusinessException.class,
                () -> service(Map.of(), null).resolve("fabric", "1.20.1", null));
    }

    @Test
    void fabricRejectsIllegalDeclaredVersion() {
        assertThrows(McpanelBusinessException.class,
                () -> service(Map.of(), null).resolve("fabric", "1.20.1", "../../etc"));
    }

    @Test
    void quiltResolvesViaQuiltMeta() {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://meta.quiltmc.org/v3/versions/loader",
                "[{\"version\":\"0.26.0\",\"stable\":true}]");
        responses.put("https://meta.quiltmc.org/v3/versions/installer",
                "[{\"version\":\"1.0.0\",\"stable\":true}]");
        CoreDownloadService.CoreDownloadPlan plan =
                service(responses, null).resolve("quilt", "1.21.1", null);
        assertEquals("https://meta.quiltmc.org/v3/versions/loader/1.21.1/0.26.0/1.0.0/server/jar", plan.url());
        assertEquals("official:quilt", plan.source());
    }

    @Test
    void loaderCoresBypassMirrorEvenWhenConfigured() {
        Map<String, String> responses = new LinkedHashMap<>();
        // 镜像上存在 fabric「latest」也不可用：它无法表达 loader 版本约束
        responses.put("https://mirror.example/api/v3/fabric/1.20.1/latest",
                "{\"data\":{\"url\":\"https://mirror.example/fabric-pinned-old.jar\",\"build\":\"42\"}}");
        CoreDownloadService.CoreDownloadPlan plan =
                service(responses, "https://mirror.example").resolve("fabric", "1.20.1", "0.18.2");
        assertTrue(plan.url().startsWith("https://meta.fabricmc.net/"));
        assertEquals("official:fabric", plan.source());
    }

    @Test
    void paperStillPrefersMirrorWhenConfigured() {
        Map<String, String> responses = new LinkedHashMap<>();
        responses.put("https://mirror.example/api/v3/paper/1.21.4/latest",
                "{\"data\":{\"url\":\"https://mirror.example/paper-1.21.4-87.jar\",\"build\":\"87\"}}");
        CoreDownloadService.CoreDownloadPlan plan =
                service(responses, "https://mirror.example").resolve("paper", "1.21.4", null);
        assertEquals("fastmirror", plan.source());
        assertEquals("https://mirror.example/paper-1.21.4-87.jar", plan.url());
    }

    @Test
    void catalogContainsQuilt() {
        CoreDownloadService service = service(Map.of(), null);
        assertTrue(service.listCores().stream().anyMatch(row -> "quilt".equals(row.get("id"))));
    }
}
