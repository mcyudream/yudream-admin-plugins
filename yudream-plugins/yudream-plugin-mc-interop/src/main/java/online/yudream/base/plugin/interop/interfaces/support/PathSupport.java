package online.yudream.base.plugin.interop.interfaces.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** 插件相对路径段解析（对齐 ymcl PathSegments 用法）。 */
public final class PathSupport {

    private PathSupport() {
    }

    /** 按 0 起下标取路径段；越界返回 null。 */
    public static String segment(String path, int index) {
        String[] segments = normalize(path).split("/");
        if (index < 0 || index >= segments.length) {
            return null;
        }
        return URLDecoder.decode(segments[index], StandardCharsets.UTF_8);
    }

    private static String normalize(String path) {
        String value = path == null ? "" : path;
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}
