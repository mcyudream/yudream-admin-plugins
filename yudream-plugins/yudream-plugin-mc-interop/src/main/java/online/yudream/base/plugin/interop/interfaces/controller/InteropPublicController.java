package online.yudream.base.plugin.interop.interfaces.controller;

import online.yudream.base.plugin.interop.application.service.CatalogService;
import online.yudream.base.plugin.interop.infrastructure.support.RateLimiter;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mc-interop v1 统一出口（匿名 + 限流 + ETag/304 + 公共缓存，协议 §2）。
 *
 * <p>部署形态：yda 宿主 McInteropWellKnownController 原生聚合提供
 * /.well-known/mc-interop-v1.json（进程内调度本端点并与 peers 文档合并，
 * ETag/缓存），无需任何反代；本端点为原始单站出口，供宿主聚合与调试直用。</p>
 */
public class InteropPublicController {

    private final CatalogService catalog;
    private final RateLimiter limiter = new RateLimiter(120, 60_000L);

    public InteropPublicController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @PluginHttpEndpoint(method = "GET", path = "/interop/v1/site", wrapResult = false)
    public PluginHttpResponse site(PluginHttpRequest request) {
        if (!limiter.tryAcquire(System.currentTimeMillis())) {
            return raw(429, Map.of("message", "请求过于频繁"), null);
        }
        // 协议 §3：site.id/name 必填；身份未配置时不出半填充文档（fail-closed）。
        Object siteIdValue = catalog.settings().get("siteId");
        String siteId = siteIdValue == null ? null : String.valueOf(siteIdValue);
        if (siteId == null || siteId.isBlank()) {
            return raw(503, Map.of("message", "站点尚未配置 mc-interop 站点身份"), null);
        }
        String etag = catalog.etag();
        String ifNoneMatch = header(request, "If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            return new PluginHttpResponse(304, headers(), "application/json", null, false);
        }
        // body 以插件自序列化的 JSON 文本透传：宿主对 Map 的 Long→String 定制
        // 会破坏协议要求的 size/revision 整数类型。
        return raw(200, CatalogService.Json.write(catalog.siteDocument()), etag);
    }

    private PluginHttpResponse raw(int status, Object body, String etag) {
        Map<String, String> headers = headers();
        if (etag != null) {
            headers.put("ETag", etag);
        }
        return new PluginHttpResponse(status, headers, "application/json", body, false);
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", "public, max-age=300");
        return headers;
    }

    private static String header(PluginHttpRequest request, String name) {
        List<String> values = request.headers().get(name);
        if (values == null || values.isEmpty()) {
            values = request.headers().get(name.toLowerCase());
        }
        return values == null || values.isEmpty() ? null : values.get(0).trim();
    }
}
