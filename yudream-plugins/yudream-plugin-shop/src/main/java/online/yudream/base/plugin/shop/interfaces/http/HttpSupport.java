package online.yudream.base.plugin.shop.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

class HttpSupport {

    private HttpSupport() {
    }

    static String requireUserId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return String.valueOf(request.principal().userId());
    }

    static int page(PluginHttpRequest request) {
        return intQuery(request, "page", 1);
    }

    static int size(PluginHttpRequest request) {
        return intQuery(request, "size", 10);
    }

    static int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        List<String> values = request.query().get(key);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(values.get(0).trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    static String firstQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0).trim();
    }

    /** 相对路径按段取值：/me/products/{id} → pathSegment(path, 2)。 */
    static String pathSegment(String path, int index) {
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
