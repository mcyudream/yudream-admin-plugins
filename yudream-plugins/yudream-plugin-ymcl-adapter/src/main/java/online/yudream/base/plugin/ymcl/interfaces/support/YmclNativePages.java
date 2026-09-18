package online.yudream.base.plugin.ymcl.interfaces.support;

import java.util.List;

/**
 * 启动器内置 native 页面注册表：路由、标题、图标均为页面固定属性，
 * 管理员不可编辑（导航配置只引用 pageId 做排列与启停，权限同样跟页面走）。
 */
public final class YmclNativePages {

    public record NativePage(String id, String title, String route, String icon) {
    }

    public static final String PREFIX = "native:";

    private static final List<NativePage> PAGES = List.of(
            new NativePage("home", "首页", "/", "home"),
            new NativePage("discover", "发现内容", "/browse/mod", "discover"),
            new NativePage("library", "实例库", "/library", "library"),
            new NativePage("skins", "皮肤", "/skins", "skins"),
            new NativePage("settings", "设置", "/settings", "settings"));

    private YmclNativePages() {
    }

    public static List<NativePage> all() {
        return PAGES;
    }

    public static NativePage find(String id) {
        return PAGES.stream().filter(page -> page.id().equals(id)).findFirst().orElse(null);
    }

    public static boolean isNativePageId(String pageId) {
        return pageId != null && pageId.startsWith(PREFIX)
                && find(pageId.substring(PREFIX.length())) != null;
    }

    public static String pageId(String id) {
        return PREFIX + id;
    }
}
