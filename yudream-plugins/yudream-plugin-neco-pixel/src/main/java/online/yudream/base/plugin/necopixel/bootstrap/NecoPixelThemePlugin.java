package online.yudream.base.plugin.necopixel.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.annotation.PluginTheme;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.theme.PluginThemeScope;

/**
 * NECO 像素风主题插件：不注册任何业务端点，仅通过 {@link PluginTheme} 声明
 * 公开站主题（CSS 变量 + 像素组件样式），并以 {@link PluginFrontend} 携带
 * 音效运行时 remoteEntry；主题激活时由宿主 theme-runtime 加载执行。
 */
@PluginSpec(code = NecoPixelThemePlugin.CODE, name = "NECO 像素风主题", version = NecoPixelThemePlugin.VERSION,
        description = "Minecraft 风格像素主题：接管公开站配色、像素字体与 8-bit 交互音效")
@PluginTheme(code = "neco-pixel", name = "NECO 像素风",
        description = "参考南京大学 Minecraft 协会站的像素皮肤：MC 固定配色、立体边按钮、像素标题字体与 8-bit 交互音效",
        scopes = {PluginThemeScope.SITE},
        styles = {"style.css"},
        preview = "preview.png")
@PluginFrontend(moduleName = "neco-pixel")
public final class NecoPixelThemePlugin implements YuDreamPlugin {

    public static final String CODE = "neco-pixel";
    public static final String VERSION = "1.0.0";
}
