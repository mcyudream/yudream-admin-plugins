package online.yudream.base.plugin.launcher.domain.valobj;

import java.util.List;

/**
 * 站点下发给 YMCL 的外观与导航配置。页面内容仍由 {@code LauncherProvider} 贡献。
 */
public record YmclLauncherChrome(
        String displayName,
        String logoUrl,
        String backgroundUrl,
        List<YmclNavNode> navTree
) {
    public static YmclLauncherChrome defaults() {
        return new YmclLauncherChrome("", "", "", List.of(defaultSiteTab()));
    }

    public static YmclNavNode defaultSiteTab() {
        return new YmclNavNode("tab-site", YmclNavNode.KIND_TAB, "站点", "i-ri:planet-line", "", true, List.of());
    }
}
