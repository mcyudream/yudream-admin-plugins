package online.yudream.base.plugin.forum.interfaces.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public final class HttpSupport {
    private HttpSupport() {}
    public static String first(PluginHttpRequest r, String key) { var v = r.query().get(key); return v == null || v.isEmpty() ? "" : v.getFirst(); }
    public static int page(PluginHttpRequest r) { return intParam(first(r, "page"), 1, 1, 100000); }
    public static int size(PluginHttpRequest r) { return intParam(first(r, "size"), 20, 1, 100); }
    private static int intParam(String raw, int fallback, int min, int max) { try { return raw == null || raw.isBlank() ? fallback : Math.max(min, Math.min(max, Integer.parseInt(raw))); } catch (Exception e) { return fallback; } }
    public static List<String> segments(String path) { String p = path == null ? "" : path.replaceFirst("^/+", "").replaceFirst("/+$", ""); if (p.isBlank()) return List.of(); List<String> out = new ArrayList<>(); for (String s : p.split("/")) out.add(URLDecoder.decode(s, StandardCharsets.UTF_8)); return out; }
    public static String segmentAfter(String path, String anchor) { var s = segments(path); for (int i=0; i<s.size()-1; i++) if (anchor.equals(s.get(i))) return s.get(i+1); return null; }
    public static PluginHttpResponse guard(Supplier<PluginHttpResponse> action) { try { return action.get(); } catch (NotFoundException e) { return PluginHttpResponse.rawJson(404, Map.of("message", e.getMessage())); } catch (IllegalArgumentException | IllegalStateException e) { return PluginHttpResponse.rawJson(400, Map.of("message", e.getMessage())); } }
    public static long userId(PluginHttpRequest request) { if (request.principal() == null || request.principal().userId() == null) throw new IllegalStateException("需要登录"); return request.principal().userId(); }
    public static String userIdText(PluginHttpRequest request) { return String.valueOf(userId(request)); }
    public static class NotFoundException extends RuntimeException { public NotFoundException(String message) { super(message); } }
}
