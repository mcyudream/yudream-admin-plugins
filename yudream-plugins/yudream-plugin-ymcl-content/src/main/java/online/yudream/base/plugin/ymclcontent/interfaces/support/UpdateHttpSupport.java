package online.yudream.base.plugin.ymclcontent.interfaces.support;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 更新平台 HTTP 小工具：路径段、query/header、origin 解析与版本比较。
 */
public final class UpdateHttpSupport {

    public static final String PLUGIN_API_PREFIX = "/api/plugins/ymcl-content";
    public static final String UPDATE_API_PREFIX = PLUGIN_API_PREFIX + "/v1/update";

    private UpdateHttpSupport() {
    }

    public static String firstQuery(PluginHttpRequest request, String name) {
        if (request == null || request.query() == null) {
            return null;
        }
        List<String> values = request.query().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return blankToNull(values.get(0));
    }

    public static String firstHeader(PluginHttpRequest request, String name) {
        if (request == null || request.headers() == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                return values == null || values.isEmpty() ? blankToNull(values.get(0)) : null;
            }
        }
        return null;
    }

    /** Query 优先，其次 Header，最后默认值。 */
    public static String queryOrHeader(PluginHttpRequest request, String queryName, String headerName,
            String fallback) {
        String value = firstQuery(request, queryName);
        if (value != null) {
            return value;
        }
        value = firstHeader(request, headerName);
        return value != null ? value : fallback;
    }

    public static List<String> pathSegments(String path) {
        String value = path == null ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return List.of();
        }
        String[] parts = value.split("/");
        List<String> segments = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                segments.add(URLDecoder.decode(part, StandardCharsets.UTF_8));
            }
        }
        return segments;
    }

    /**
     * 取 path 中 marker 之后第 offset 段（0 = marker 后第一段）。
     * 例：/v1/update/files/1.0.0/a.zip，marker=files, offset=0 → 1.0.0
     */
    public static String segmentAfter(String path, String marker, int offset) {
        List<String> segments = pathSegments(path);
        int index = segments.indexOf(marker);
        if (index < 0) {
            return null;
        }
        int target = index + 1 + offset;
        return target >= 0 && target < segments.size() ? segments.get(target) : null;
    }

    public static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "release";
        }
        String value = channel.trim().toLowerCase(Locale.ROOT);
        return "beta".equals(value) ? "beta" : "release";
    }

    public static String normalizeOrigin(PluginHttpRequest request) {
        Map<String, List<String>> headers = request == null ? null : request.headers();
        String scheme = firstHeaderValue(headers, "X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = "http";
        }
        String host = firstHeaderValue(headers, "X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = firstHeaderValue(headers, "Host");
        }
        if (host == null || host.isBlank()) {
            return scheme + "://localhost";
        }
        return scheme + "://" + host.trim();
    }

    public static String absoluteUpdateFileUrl(String origin, String version, String filename) {
        return trimTrailingSlash(origin) + UPDATE_API_PREFIX + "/files/"
                + urlEncode(version) + "/" + urlEncode(filename);
    }

    public static String relativeUpdateFilePath(String version, String filename) {
        return UPDATE_API_PREFIX + "/files/" + urlEncode(version) + "/" + urlEncode(filename);
    }

    /** 发布的公开更新日志页地址；externalUrl 未手动覆盖时启动器「查看完整更新日志」打开此页。 */
    public static String absoluteChangelogUrl(String origin, String version) {
        return trimTrailingSlash(origin) + UPDATE_API_PREFIX + "/changelog/" + urlEncode(version);
    }

    public static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 语义化版本粗比较：candidate &gt; current 返回 true。
     * 1.2.0 &gt; 1.1.9；1.2.0 &gt; 1.2.0-beta.1；同号预发布按字典序。
     */
    public static boolean isNewerVersion(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return true;
        }
        ParsedVersion next = ParsedVersion.parse(candidate);
        ParsedVersion now = ParsedVersion.parse(current);
        int cmp = compareNumeric(next.numbers, now.numbers);
        if (cmp != 0) {
            return cmp > 0;
        }
        boolean nextPre = next.preRelease != null;
        boolean nowPre = now.preRelease != null;
        if (!nextPre && nowPre) {
            return true;
        }
        if (nextPre && !nowPre) {
            return false;
        }
        if (!nextPre) {
            return false;
        }
        return next.preRelease.compareTo(now.preRelease) > 0;
    }

    public static boolean matchesChannel(String channel, String version) {
        String normalized = normalizeChannel(channel);
        boolean prerelease = version != null && version.contains("-");
        return "beta".equals(normalized) || !prerelease;
    }

    private static int compareNumeric(List<Integer> left, List<Integer> right) {
        int size = Math.max(left.size(), right.size());
        for (int index = 0; index < size; index++) {
            int l = index < left.size() ? left.get(index) : 0;
            int r = index < right.size() ? right.get(index) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static String firstHeaderValue(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        List<String> direct = headers.get(name);
        if (direct != null && !direct.isEmpty()) {
            return blankToNull(direct.get(0));
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return blankToNull(entry.getValue().get(0));
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private record ParsedVersion(List<Integer> numbers, String preRelease) {
        static ParsedVersion parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.startsWith("v") || value.startsWith("V")) {
                value = value.substring(1);
            }
            String pre = null;
            int dash = value.indexOf('-');
            if (dash >= 0) {
                pre = value.substring(dash + 1);
                value = value.substring(0, dash);
            }
            String plus = value.indexOf('+') >= 0 ? value.substring(0, value.indexOf('+')) : value;
            String[] parts = plus.split("\\.");
            List<Integer> numbers = new ArrayList<>();
            for (String part : parts) {
                try {
                    numbers.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    numbers.add(0);
                }
            }
            return new ParsedVersion(numbers, pre);
        }
    }
}
