package online.yudream.base.plugin.launcher.application.service;

import online.yudream.base.plugin.launcher.api.LauncherPage;
import online.yudream.base.plugin.launcher.bootstrap.LauncherAdapterPlugin;
import online.yudream.base.plugin.launcher.domain.valobj.YmclLauncherChrome;
import online.yudream.base.plugin.launcher.domain.valobj.YmclNavNode;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YmclChromeAppServiceTest {

    @Test
    void nestedTabsKeepMenusAndUnplacedPages() {
        YmclChromeAppService service = service(new AtomicReference<>());
        service.save(new YmclLauncherChrome(
                "YuDream",
                "",
                "",
                List.of(new YmclNavNode(
                        "tab-site",
                        YmclNavNode.KIND_TAB,
                        "站点",
                        "i-ri:planet-line",
                        "",
                        true,
                        List.of(new YmclNavNode(
                                "servers",
                                YmclNavNode.KIND_MENU,
                                "服务器",
                                "i-ri:server-line",
                                "",
                                true,
                                List.of(new YmclNavNode(
                                        "mc-servers",
                                        YmclNavNode.KIND_MENU,
                                        "",
                                        "",
                                        "mc.servers",
                                        true,
                                        List.of()
                                ))
                        ))
                ))
        ));

        Map<String, Object> navigation = service.navigationView(samplePages());
        assertEquals("tree", navigation.get("mode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) navigation.get("tabs");
        assertEquals(1, tabs.size());
        assertEquals("站点", tabs.getFirst().get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> menus = (List<Map<String, Object>>) tabs.getFirst().get("children");
        assertEquals(1, menus.size());
        assertEquals("服务器", menus.getFirst().get("title"));
        assertTrue(menus.getFirst().containsKey("children"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) menus.getFirst().get("children");
        assertEquals("Minecraft 服务器", children.getFirst().get("title"));
    }

    @Test
    void migrateLegacyMergedGroupsIntoSiteTab() {
        AtomicReference<Map<String, Object>> stored = new AtomicReference<>(legacyDoc());
        YmclChromeAppService service = service(stored);

        Map<String, Object> navigation = service.navigationView(samplePages());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) navigation.get("tabs");
        assertEquals(1, tabs.size());
        assertEquals("站点", tabs.getFirst().get("title"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> menus = (List<Map<String, Object>>) tabs.getFirst().get("children");
        assertEquals(2, menus.size());
        assertEquals("服务器", menus.getFirst().get("title"));
        assertEquals("站点整合包", menus.get(1).get("title"));
    }

    private static List<LauncherPage> samplePages() {
        return List.of(
                new LauncherPage("mc.servers", "Minecraft 服务器", "i-ri:server-line", 10, "server-list", "/domain/servers", "mc.servers.list", null, "minecraft-server"),
                new LauncherPage("ymcl.packs", "站点整合包", "i-ri:box-3-line", 20, "pack-list", "/domain/packs", "ymcl.packs.list", null, LauncherAdapterPlugin.CODE)
        );
    }

    private static Map<String, Object> legacyDoc() {
        Map<String, Object> group = new HashMap<>();
        group.put("code", "servers");
        group.put("title", "服务器");
        group.put("icon", "i-ri:server-line");
        group.put("pageCodes", List.of("mc.servers"));
        Map<String, Object> doc = new HashMap<>();
        doc.put("displayName", "YuDream");
        doc.put("navMode", "merged");
        doc.put("mergedTabTitle", "站点");
        doc.put("tabPageCodes", List.of());
        doc.put("hiddenPageCodes", List.of());
        doc.put("menuGroups", List.of(group));
        return doc;
    }

    private static YmclChromeAppService service(AtomicReference<Map<String, Object>> stored) {
        PluginDocumentStore documents = (PluginDocumentStore) Proxy.newProxyInstance(
                PluginDocumentStore.class.getClassLoader(),
                new Class<?>[]{PluginDocumentStore.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        stored.set(new HashMap<>((Map<String, Object>) args[2]));
                        return null;
                    }
                    if ("findById".equals(method.getName())) {
                        Map<String, Object> doc = stored.get();
                        return doc == null ? Optional.empty() : Optional.of(new HashMap<>(doc));
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        return new YmclChromeAppService(documents);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class || type == int.class) {
            return 0;
        }
        if (Optional.class.equals(type)) {
            return Optional.empty();
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        return null;
    }
}
