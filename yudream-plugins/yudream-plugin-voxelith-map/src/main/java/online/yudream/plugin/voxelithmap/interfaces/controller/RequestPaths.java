package online.yudream.plugin.voxelithmap.interfaces.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 插件请求路径的分段与解码。
 *
 * <p><b>宿主交给插件的 path 是「未解码」的。</b>宿主 {@code PluginDispatchController#pluginPath}
 * 直接取 {@code HttpServletRequest.getRequestURI()} 再剪掉 {@code /api/plugins/{code}} 前缀，
 * 而 servlet 规范里 {@code getRequestURI()} 明确不做百分号解码。于是
 * {@code /admin/maps/%E4%B8%87%E4%BD%9B%E5%B1%B1%E5%9C%B0%E5%BD%A2-27c4c} 到插件手里时
 * mapId 仍是那串 {@code %E4%B8%87...}，拿去查库只会得到
 * 「地图不存在: %E4%B8%87...」——凡是 id 里含非 ASCII（本插件刻意允许中文名进 id）
 * 或含转义字符的地图，详情页 / 预览 / 全景 / worker 全部点不开。
 *
 * <p>宿主的路由匹配不受影响：{@code {mapId}} 是按段匹配的，与段的内容无关。
 * 所以修复放在插件侧——<b>凡是从路径里取出来的参数，都要过一遍这里的解码。</b>
 */
final class RequestPaths {

    private RequestPaths() {
    }

    /**
     * 按 {@code /} 切段并逐段解码。
     *
     * <p>保留尾随空段（{@code split(..., -1)}），这样各段下标与 {@link #segment} 的语义一致：
     * {@code /a/{p0}/{p1}} → 下标 {@code 0} 是前导空串、{@code 1} 是 {@code a}、
     * {@code 2} 即 {@code p0}。</p>
     *
     * <p>查询串（{@code ?} 起）先剪掉：{@code getRequestURI()} 本不该带查询串，但只要前面经过
     * 一次带查询的转发/重写它就会混进最后一段，让「按段拼对象 key」的端点（瓦片、图集页）
     * 按字面量找不到文件。剪掉无损——插件端点不读查询参数。</p>
     */
    static String[] segments(String path) {
        String clean = path == null ? "" : path;
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        String[] raw = clean.split("/", -1);
        String[] decoded = new String[raw.length];
        for (int index = 0; index < raw.length; index++) {
            decoded[index] = decode(raw[index]);
        }
        return decoded;
    }

    /**
     * 取第 {@code index} 个路径参数：{@code /a/{p0}/{p1}} 里 {@code p0} 的 index 是 {@code 0}。
     *
     * @throws IllegalArgumentException 该段缺失或为空（宿主会映射成 400 + 消息）
     */
    static String segment(String path, int index) {
        String[] segments = segments(path);
        if (segments.length <= index + 1 || segments[index + 1].isBlank()) {
            throw new IllegalArgumentException("路径参数缺失");
        }
        return segments[index + 1];
    }

    /**
     * 把第 {@code from} 个下标起的段重新拼成相对路径（各段均已解码）。
     *
     * <p>用来替代「在原始 path 上做 {@code substring}」的写法：后者会把未解码的
     * 中文/转义段原样带进对象 key，越过了这里的兜底。</p>
     */
    static String join(String path, int from) {
        String[] segments = segments(path);
        if (segments.length <= from) {
            return "";
        }
        return String.join("/", Arrays.copyOfRange(segments, from, segments.length));
    }

    /**
     * 百分号解码，并刻意不把 {@code +} 当空格——这里是 path 段而不是 query，
     * {@code +} 是字面量字符。先把 {@code +} 转义成 {@code %2B} 再交给
     * {@link URLDecoder}，就不会被误伤。
     *
     * <p>没有 {@code %} 的段直接原样返回；转义非法（例如 {@code %zz}）也原样返回——
     * 宁可让后续按「查不到这个 id」报 404/400，也不要在这里抛 500。</p>
     */
    static String decode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
