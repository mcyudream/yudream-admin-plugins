package online.yudream.base.plugin.necopixel.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.annotation.PluginTheme;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.theme.PluginThemeScope;

/**
 * NECO 像素风主题插件：不注册任何业务端点。2.0 起主题页面全部为插件自带的
 * Vue 原生页面（{@link PluginTheme#chromeComponent()} 接管公开站页头页脚，
 * {@link PluginTheme#homeComponent()} 接管公开站首页，
 * {@link PluginRoute#siteNav()} 公开路由承载服务器/活动平台），
 * 不再依赖 CMS 模板引擎与首页方案；主题配置 schema（theme-config.json）保留，
 * 供「主题中心 → 配置」可视化维护首屏文案、介绍项、静态服务器与友情链接。
 * 音效/强调色切换器等运行时经 {@link PluginFrontend} 的 remoteEntry
 * 在主题激活期间由宿主 theme-runtime 加载执行。
 */
@PluginSpec(code = NecoPixelThemePlugin.CODE, name = "NECO 像素风主题", version = NecoPixelThemePlugin.VERSION,
        description = "Minecraft 风格像素主题：Vue 原生 chrome/首页接管公开站，服务器列表与活动页复刻 NMO，像素字体与 MC 点击音效")
@PluginTheme(code = "neco-pixel", name = "NECO 像素风",
        description = "参考南京大学 Minecraft 协会站的像素皮肤：主题自管导航、MC 深色配色、服务器列表 ping 条与活动 3D 卡片",
        scopes = {PluginThemeScope.SITE},
        styles = {"style.css"},
        preview = "preview.png",
        homeComponent = "theme/Home",
        chromeComponent = "theme/Chrome",
        configSchema = "theme-config.json")
@PluginFrontend(moduleName = "neco-pixel", routes = {
        @PluginRoute(path = "/servers", name = "neco-pixel-servers", title = "服务器",
                icon = "i-ri:server-line", component = "theme/Servers", hideInMenu = true, publicAccess = true, siteNav = true),
        @PluginRoute(path = "/activities", name = "neco-pixel-activities", title = "活动平台",
                icon = "i-ri:flag-line", component = "theme/Activities", hideInMenu = true, publicAccess = true, siteNav = true),
        @PluginRoute(path = "/activities/:id", name = "neco-pixel-activity-detail", title = "活动详情",
                icon = "i-ri:flag-line", component = "theme/ActivityDetail", hideInMenu = true, publicAccess = true, siteNav = true)
})
public final class NecoPixelThemePlugin implements YuDreamPlugin {

    public static final String CODE = "neco-pixel";
    public static final String VERSION = "2.0.10";
}
