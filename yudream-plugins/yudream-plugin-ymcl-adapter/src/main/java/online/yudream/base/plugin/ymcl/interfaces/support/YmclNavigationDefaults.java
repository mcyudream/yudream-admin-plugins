package online.yudream.base.plugin.ymcl.interfaces.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置默认域导航树（chrome/navigation 未配置时的种子数据，也是 manifest
 * 的回退值）。配置项支持两种节点：
 * <ul>
 *   <li>{@code kind=page}：引用页面注册表 pageId，路由/权限随页面固定，
 *       可选覆盖 title/icon 做导航展示；</li>
 *   <li>{@code kind=directory}：纯目录节点（无路由），仅 title/icon 与子项。</li>
 * </ul>
 */
public final class YmclNavigationDefaults {

    private YmclNavigationDefaults() {
    }

    public static List<Map<String, Object>> items() {
        List<String> order = List.of("home", "discover", "library", "skins", "settings");
        List<Map<String, Object>> items = new ArrayList<>();
        int sort = 0;
        for (String id : order) {
            items.add(item(YmclNativePages.pageId(id), sort));
            sort += 10;
        }
        return items;
    }

    public static Map<String, Object> item(String pageId, int sort) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "page");
        item.put("pageId", pageId);
        item.put("enabled", Boolean.TRUE);
        item.put("sort", sort);
        item.put("children", new ArrayList<Map<String, Object>>());
        return item;
    }

    public static Map<String, Object> directory(String id, String title, String icon, int sort) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "directory");
        item.put("id", id);
        item.put("title", title);
        if (icon != null && !icon.isBlank()) {
            item.put("icon", icon);
        }
        item.put("enabled", Boolean.TRUE);
        item.put("sort", sort);
        item.put("children", new ArrayList<Map<String, Object>>());
        return item;
    }
}
