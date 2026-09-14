package online.yudream.base.plugin.launcher.application.service;

import online.yudream.base.plugin.launcher.api.LauncherPage;
import online.yudream.base.plugin.launcher.domain.valobj.YmclLauncherChrome;
import online.yudream.base.plugin.launcher.domain.valobj.YmclNavNode;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 站点启动器外观/导航配置。页面列表仍来自 {@link LauncherProviderAggregator}。
 */
public class YmclChromeAppService {

    private static final String COLLECTION = "ymcl_chrome";
    private static final String DOC_ID = "global";

    private final PluginDocumentStore documents;

    public YmclChromeAppService(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public YmclLauncherChrome load() {
        return documents.findById(COLLECTION, DOC_ID)
                .map(this::fromDoc)
                .orElseGet(YmclLauncherChrome::defaults);
    }

    public YmclLauncherChrome loadForPages(List<LauncherPage> pages) {
        return documents.findById(COLLECTION, DOC_ID)
                .map(doc -> hydrate(fromDoc(doc), doc, pages))
                .orElseGet(() -> new YmclLauncherChrome(
                        "",
                        "",
                        "",
                        appendUnplaced(List.of(YmclLauncherChrome.defaultSiteTab()), pages)
                ));
    }

    public void save(YmclLauncherChrome chrome) {
        Map<String, Object> doc = new LinkedHashMap<>();
        putText(doc, "displayName", chrome.displayName());
        putText(doc, "logoUrl", chrome.logoUrl());
        putText(doc, "backgroundUrl", chrome.backgroundUrl());
        doc.put("navTree", serializeTree(normalizeTree(chrome.navTree()), true));
        documents.save(COLLECTION, DOC_ID, doc);
    }

    public Map<String, Object> adminView(List<LauncherPage> pages) {
        YmclLauncherChrome chrome = loadForPages(pages);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("chrome", chromeView(chrome));
        view.put("pages", pages.stream().map(this::pageView).toList());
        return view;
    }

    public Map<String, Object> themeView() {
        YmclLauncherChrome chrome = load();
        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("displayName", text(chrome.displayName()));
        theme.put("logoUrl", text(chrome.logoUrl()));
        theme.put("backgroundUrl", text(chrome.backgroundUrl()));
        return theme;
    }

    public Map<String, Object> navigationView(List<LauncherPage> pages) {
        YmclLauncherChrome chrome = loadForPages(pages);
        Map<String, LauncherPage> byCode = pagesByCode(pages);
        List<Map<String, Object>> tabs = new ArrayList<>();
        for (YmclNavNode tab : normalizeTree(chrome.navTree())) {
            Map<String, Object> item = toNavItem(tab, byCode, true);
            if (item != null) {
                tabs.add(item);
            }
        }
        Map<String, Object> navigation = new LinkedHashMap<>();
        navigation.put("mode", "tree");
        navigation.put("tabs", tabs);
        if (tabs.isEmpty()) {
            navigation.put("mergedTab", navItem("tab-site", "站点", "i-ri:planet-line", "/domain", ""));
            navigation.put("menu", List.of());
        } else {
            Map<String, Object> first = tabs.getFirst();
            navigation.put("mergedTab", copyWithoutChildren(first));
            Object children = first.get("children");
            navigation.put("menu", children instanceof List<?> list ? list : List.of());
        }
        return navigation;
    }

    private Map<String, Object> chromeView(YmclLauncherChrome chrome) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("displayName", text(chrome.displayName()));
        view.put("logoUrl", text(chrome.logoUrl()));
        view.put("backgroundUrl", text(chrome.backgroundUrl()));
        view.put("navTree", serializeTree(normalizeTree(chrome.navTree()), true));
        return view;
    }

    private Map<String, Object> pageView(LauncherPage page) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("code", page.code());
        view.put("title", page.title());
        view.put("icon", text(page.icon()));
        view.put("type", page.type());
        view.put("path", page.path());
        view.put("providerCode", text(page.providerCode()));
        view.put("navOrder", page.navOrder());
        view.put("dataSourceCode", text(page.dataSourceCode()));
        return view;
    }

    private Map<String, Object> toNavItem(YmclNavNode node, Map<String, LauncherPage> byCode, boolean root) {
        if (node == null || !node.visible()) {
            return null;
        }
        LauncherPage page = byCode.get(text(node.pageCode()));
        String title = text(node.title());
        if (title.isBlank() && page != null) {
            title = text(page.title());
        }
        if (title.isBlank()) {
            return null;
        }
        String icon = text(node.icon());
        if (icon.isBlank() && page != null) {
            icon = text(page.icon());
        }
        if (icon.isBlank()) {
            icon = root ? "i-ri:planet-line" : "i-ri:file-line";
        }
        List<Map<String, Object>> children = new ArrayList<>();
        for (YmclNavNode child : node.childNodes()) {
            Map<String, Object> item = toNavItem(child, byCode, false);
            if (item != null) {
                children.add(item);
            }
        }
        String path = page != null ? text(page.path()) : firstPath(children);
        Map<String, Object> item = navItem(
                text(node.code()),
                title,
                icon,
                path,
                page == null ? "" : page.code()
        );
        item.put("kind", node.normalizedKind(root));
        if (!children.isEmpty()) {
            item.put("children", children);
        }
        return item;
    }

    private String firstPath(List<Map<String, Object>> children) {
        for (Map<String, Object> child : children) {
            String path = text(String.valueOf(child.getOrDefault("path", "")));
            if (!path.isBlank()) {
                return path;
            }
        }
        return "";
    }

    private Map<String, Object> copyWithoutChildren(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.remove("children");
        return copy;
    }

    private Map<String, Object> navItem(String code, String title, String icon, String path, String pageCode) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", text(code));
        item.put("title", text(title));
        item.put("icon", text(icon));
        item.put("path", text(path));
        item.put("pageCode", text(pageCode));
        return item;
    }

    private YmclLauncherChrome fromDoc(Map<String, Object> doc) {
        List<YmclNavNode> tree;
        if (doc.containsKey("navTree")) {
            tree = nodesFrom(doc.get("navTree"), true);
        } else {
            tree = migrateLegacy(doc);
        }
        if (tree.isEmpty()) {
            tree = List.of(YmclLauncherChrome.defaultSiteTab());
        }
        return new YmclLauncherChrome(
                text(stringValue(doc, "displayName")),
                text(stringValue(doc, "logoUrl")),
                text(stringValue(doc, "backgroundUrl")),
                tree
        );
    }

    private YmclLauncherChrome hydrate(YmclLauncherChrome chrome, Map<String, Object> doc, List<LauncherPage> pages) {
        if (doc.containsKey("navTree")) {
            return chrome;
        }
        if ("tabs".equals(text(stringValue(doc, "navMode")))) {
            Set<String> hidden = new LinkedHashSet<>(sanitizeCodes(stringList(doc.get("hiddenPageCodes"))));
            List<YmclNavNode> tabs = new ArrayList<>();
            int index = 0;
            for (LauncherPage page : pages == null ? List.<LauncherPage>of() : pages) {
                if (page == null || page.code() == null || page.code().isBlank() || hidden.contains(page.code())) {
                    continue;
                }
                tabs.add(new YmclNavNode(
                        "tab-" + index,
                        YmclNavNode.KIND_TAB,
                        text(page.title()),
                        text(page.icon()),
                        page.code(),
                        true,
                        List.of()
                ));
                index++;
            }
            return new YmclLauncherChrome(chrome.displayName(), chrome.logoUrl(), chrome.backgroundUrl(),
                    tabs.isEmpty() ? List.of(YmclLauncherChrome.defaultSiteTab()) : tabs);
        }
        return new YmclLauncherChrome(
                chrome.displayName(),
                chrome.logoUrl(),
                chrome.backgroundUrl(),
                appendUnplaced(chrome.navTree(), pages)
        );
    }

    private List<YmclNavNode> appendUnplaced(List<YmclNavNode> tree, List<LauncherPage> pages) {
        Set<String> used = new LinkedHashSet<>();
        collectPageCodes(tree, used);
        List<YmclNavNode> extra = new ArrayList<>();
        for (LauncherPage page : pages == null ? List.<LauncherPage>of() : pages) {
            if (page == null || page.code() == null || page.code().isBlank() || used.contains(page.code())) {
                continue;
            }
            extra.add(pageMenu(page.code(), page.code()));
        }
        if (extra.isEmpty()) {
            return tree == null || tree.isEmpty() ? List.of(YmclLauncherChrome.defaultSiteTab()) : tree;
        }
        List<YmclNavNode> result = new ArrayList<>(tree == null ? List.of() : tree);
        if (result.isEmpty()) {
            result.add(YmclLauncherChrome.defaultSiteTab());
        }
        YmclNavNode first = result.getFirst();
        List<YmclNavNode> children = new ArrayList<>(first.childNodes());
        children.addAll(extra);
        result.set(0, new YmclNavNode(
                first.code(),
                first.normalizedKind(true),
                first.title(),
                first.icon(),
                first.pageCode(),
                first.visible(),
                children
        ));
        return result;
    }

    private void collectPageCodes(List<YmclNavNode> nodes, Set<String> used) {
        if (nodes == null) {
            return;
        }
        for (YmclNavNode node : nodes) {
            if (node == null) {
                continue;
            }
            String pageCode = text(node.pageCode());
            if (!pageCode.isBlank()) {
                used.add(pageCode);
            }
            collectPageCodes(node.childNodes(), used);
        }
    }

    private List<YmclNavNode> migrateLegacy(Map<String, Object> doc) {
        Set<String> hidden = new LinkedHashSet<>(sanitizeCodes(stringList(doc.get("hiddenPageCodes"))));
        Set<String> extraTabs = new LinkedHashSet<>(sanitizeCodes(stringList(doc.get("tabPageCodes"))));
        extraTabs.removeAll(hidden);
        String mode = text(stringValue(doc, "navMode"));
        String mergedTitle = text(stringValue(doc, "mergedTabTitle"));
        if (mergedTitle.isBlank()) {
            mergedTitle = "站点";
        }
        List<YmclNavNode> tabs = new ArrayList<>();
        if ("tabs".equals(mode)) {
            return tabs;
        }
        List<YmclNavNode> menus = new ArrayList<>();
        Object rawGroups = doc.get("menuGroups");
        if (rawGroups instanceof List<?> groups) {
            int index = 0;
            for (Object item : groups) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> group = typedMap(map);
                String title = text(stringValue(group, "title"));
                if (title.isBlank()) {
                    continue;
                }
                List<YmclNavNode> children = new ArrayList<>();
                for (String pageCode : sanitizeCodes(stringList(group.get("pageCodes")))) {
                    if (hidden.contains(pageCode) || extraTabs.contains(pageCode)) {
                        continue;
                    }
                    children.add(pageMenu(pageCode, pageCode));
                }
                String code = text(stringValue(group, "code"));
                menus.add(new YmclNavNode(
                        code.isBlank() ? "menu-group-" + index : code,
                        YmclNavNode.KIND_MENU,
                        title,
                        text(stringValue(group, "icon")),
                        "",
                        true,
                        children
                ));
                index++;
            }
        }
        tabs.add(new YmclNavNode(
                "tab-site",
                YmclNavNode.KIND_TAB,
                mergedTitle,
                "i-ri:planet-line",
                "",
                true,
                menus
        ));
        int tabIndex = 1;
        for (String pageCode : extraTabs) {
            tabs.add(new YmclNavNode(
                    "tab-" + tabIndex,
                    YmclNavNode.KIND_TAB,
                    "",
                    "",
                    pageCode,
                    true,
                    List.of()
            ));
            tabIndex++;
        }
        return tabs;
    }

    private YmclNavNode pageMenu(String code, String pageCode) {
        return new YmclNavNode(code, YmclNavNode.KIND_MENU, "", "", pageCode, true, List.of());
    }

    private List<Map<String, Object>> serializeTree(List<YmclNavNode> nodes, boolean root) {
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 0;
        for (YmclNavNode node : nodes == null ? List.<YmclNavNode>of() : nodes) {
            if (node == null) {
                continue;
            }
            String kind = node.normalizedKind(root);
            String title = text(node.title());
            String pageCode = text(node.pageCode());
            List<Map<String, Object>> children = serializeTree(node.childNodes(), false);
            if (title.isBlank() && pageCode.isBlank() && children.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            String code = text(node.code());
            item.put("code", code.isBlank() ? (root ? "tab-" : "menu-") + index : code);
            item.put("kind", kind);
            item.put("title", title);
            putText(item, "icon", node.icon());
            putText(item, "pageCode", pageCode);
            item.put("visible", node.visible());
            item.put("children", children);
            result.add(item);
            index++;
        }
        return result;
    }

    private List<YmclNavNode> normalizeTree(List<YmclNavNode> nodes) {
        return nodesFrom(serializeTree(nodes, true), true);
    }

    private List<YmclNavNode> nodesFrom(Object raw, boolean root) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<YmclNavNode> nodes = new ArrayList<>();
        AtomicInteger index = new AtomicInteger();
        Set<String> used = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> entry = typedMap(map);
            String kind = root ? YmclNavNode.KIND_TAB : YmclNavNode.KIND_MENU;
            String title = text(stringValue(entry, "title"));
            String pageCode = text(stringValue(entry, "pageCode"));
            List<YmclNavNode> children = nodesFrom(entry.get("children"), false);
            if (title.isBlank() && pageCode.isBlank() && children.isEmpty()) {
                continue;
            }
            String prefix = root ? "tab-" : "menu-";
            String code = uniqueCode(text(stringValue(entry, "code")), prefix + index.getAndIncrement(), used);
            boolean visible = entry.get("visible") instanceof Boolean flag ? flag : true;
            nodes.add(new YmclNavNode(
                    code,
                    kind,
                    title,
                    text(stringValue(entry, "icon")),
                    pageCode,
                    visible,
                    children
            ));
        }
        return nodes;
    }

    private String uniqueCode(String preferred, String fallback, Set<String> used) {
        String base = preferred.isBlank() ? fallback : preferred;
        String code = base;
        int suffix = 2;
        while (!used.add(code)) {
            code = base + "-" + suffix;
            suffix++;
        }
        return code;
    }

    private Map<String, LauncherPage> pagesByCode(List<LauncherPage> pages) {
        Map<String, LauncherPage> byCode = new LinkedHashMap<>();
        if (pages == null) {
            return byCode;
        }
        for (LauncherPage page : pages) {
            if (page != null && page.code() != null && !page.code().isBlank()) {
                byCode.put(page.code(), page);
            }
        }
        return byCode;
    }

    private Map<String, Object> typedMap(Map<?, ?> map) {
        Map<String, Object> entry = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) {
                entry.put(String.valueOf(k), v);
            }
        });
        return entry;
    }

    private List<String> sanitizeCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null) {
                continue;
            }
            String trimmed = code.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return List.copyOf(unique);
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private String stringValue(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private void putText(Map<String, Object> doc, String key, String value) {
        String text = text(value);
        if (!text.isEmpty()) {
            doc.put(key, text);
        }
    }
}
