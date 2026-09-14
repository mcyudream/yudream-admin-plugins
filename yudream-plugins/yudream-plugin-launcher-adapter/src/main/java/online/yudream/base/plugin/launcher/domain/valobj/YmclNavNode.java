package online.yudream.base.plugin.launcher.domain.valobj;

import java.util.List;

/**
 * 启动器导航树节点。顶级必须是 Tab，Tab 下是菜单，菜单可再分级。
 * 页面内容仍由 {@code LauncherPage} 贡献，节点只决定展示位置与外观。
 */
public record YmclNavNode(
        String code,
        String kind,
        String title,
        String icon,
        String pageCode,
        boolean visible,
        List<YmclNavNode> children
) {
    public static final String KIND_TAB = "tab";
    public static final String KIND_MENU = "menu";

    public String normalizedKind(boolean root) {
        if (root) {
            return KIND_TAB;
        }
        return KIND_TAB.equals(kind) ? KIND_TAB : KIND_MENU;
    }

    public List<YmclNavNode> childNodes() {
        return children == null ? List.of() : children;
    }
}
