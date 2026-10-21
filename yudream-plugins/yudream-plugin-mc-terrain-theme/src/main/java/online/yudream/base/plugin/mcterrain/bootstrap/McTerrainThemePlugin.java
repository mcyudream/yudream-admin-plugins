package online.yudream.base.plugin.mcterrain.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.annotation.PluginTheme;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.theme.PluginThemeScope;

/**
 * 方块地形主题插件：不注册任何业务端点与前端页面。主题是一份网站工作台站点，
 * 经 {@link PluginTheme#homePreset()} 指向的 {@code site-preset.json}（studioPackage）随 JAR 分发，
 * 媒体放在同一资产目录、按插件资产路由引用；SITE 主题激活时宿主在该主题尚无站点时导入并发布，
 * 之后由管理员在网站工作台与「站点内容」页维护，升级插件不覆盖已有站点。
 */
@PluginSpec(code = McTerrainThemePlugin.CODE, name = "方块地形主题", version = McTerrainThemePlugin.VERSION,
        description = "Minecraft 风格公开站主题：六个地形世界、底部物品栏导航、像素面板与音效，站点由网站工作台编辑")
@PluginTheme(code = "mc-terrain", name = "方块地形",
        description = "六个地形世界与底部物品栏导航的 Minecraft 风格公开站；服务器、活动等由对应插件页面承载",
        scopes = {PluginThemeScope.SITE},
        styles = {"theme.css"},
        preview = "preview.webp",
        homePreset = "site-preset.json")
public final class McTerrainThemePlugin implements YuDreamPlugin {

    public static final String CODE = "mc-terrain-theme";
    public static final String VERSION = "1.0.0";
}
