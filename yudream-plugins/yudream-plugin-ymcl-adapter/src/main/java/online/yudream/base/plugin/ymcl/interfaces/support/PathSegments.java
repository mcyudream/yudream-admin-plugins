package online.yudream.base.plugin.ymcl.interfaces.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 插件端点路径段的解析帮助：宿主约束端点方法最多只能声明
 * PluginHttpRequest/PluginContext，因此路径模板变量从 request.path()
 * 手动解析（相对插件挂载前缀 /api/plugins/ymcl-adapter）。
 */
public final class PathSegments {

    private PathSegments() {
    }

    /** 返回路径的第 {@code index} 段（0 起、去前导斜杠、URL 解码）；越界返回 null。 */
    public static String segment(String path, int index) {
        String value = path == null ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        String[] segments = value.split("/");
        if (index < 0 || index >= segments.length) {
            return null;
        }
        return URLDecoder.decode(segments[index], StandardCharsets.UTF_8);
    }
}
