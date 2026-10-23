package online.yudream.base.plugin.skin.interfaces.controller;

import online.yudream.base.plugin.skin.application.service.OfficialBindService;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 正版绑定（/me/** 用户面 + /official-bind/callback 公开回调 + /admin 设置）。 */
public class OfficialBindController {

    private final OfficialBindService service;

    public OfficialBindController(OfficialBindService service) {
        this.service = service;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/official-bind/status",
            permission = "plugin:yudream-skin:user")
    public Object status(PluginHttpRequest request) {
        return service.status(ownerId(request));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/official-bind/start",
            permission = "plugin:yudream-skin:user")
    public Object start(PluginHttpRequest request) {
        return service.start(ownerId(request), System.currentTimeMillis());
    }

    /**
     * 正版会话直通：仅供已认证的原生启动器拿到本人的短期 Minecraft Java token；
     * 不走宿主 Result 信封，且禁止中间缓存。启动器应存入操作系统安全存储并按 expiresAt 续签。
     */
    @PluginHttpEndpoint(method = "POST", path = "/me/official-bind/session",
            permission = "plugin:yudream-skin:user", wrapResult = false)
    public PluginHttpResponse mintSession(PluginHttpRequest request) {
        try {
            Map<String, Object> body = service.mintOfficialSession(ownerId(request));
            return new PluginHttpResponse(200, Map.of(
                    "Cache-Control", "no-store, max-age=0",
                    "Pragma", "no-cache"
            ), "application/json", body, false);
        } catch (IllegalArgumentException e) {
            return PluginHttpResponse.rawJson(400, Map.of("error", "official-session-failed", "errorMessage", e.getMessage()));
        }
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/official-bind",
            permission = "plugin:yudream-skin:user")
    public Object unbind(PluginHttpRequest request) {
        service.unbind(ownerId(request));
        return Map.of("unbound", true);
    }

    @PluginHttpEndpoint(method = "GET", path = "/official-bind/callback", wrapResult = false)
    public PluginHttpResponse callback(PluginHttpRequest request) {
        String code = firstQuery(request, "code");
        String state = firstQuery(request, "state");
        String error = firstQuery(request, "error");
        try {
            if (error != null && !error.isBlank()) {
                return callbackPage(false, "官方授权被取消或失败，请重试");
            }
            if (code == null || code.isBlank()) {
                return callbackPage(false, "授权回调缺少 code 参数");
            }
            service.callback(state, code, System.currentTimeMillis());
            return callbackPage(true, "正版角色已绑定，可以关闭此窗口。");
        } catch (IllegalArgumentException e) {
            return callbackPage(false, e.getMessage());
        } catch (IllegalStateException e) {
            return callbackPage(false, e.getMessage());
        }
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/official-bind/settings",
            permission = "plugin:yudream-skin:manage")
    public Object settings() {
        return service.settings();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/official-bind/settings",
            permission = "plugin:yudream-skin:manage")
    public Object saveSettings(PluginHttpRequest request) {
        Map<String, Object> body = readMap(request.body());
        return service.saveSettings(Boolean.TRUE.equals(body.get("enabled")),
                body.get("clientId") == null ? null : String.valueOf(body.get("clientId")),
                body.get("clientSecret") == null ? null : String.valueOf(body.get("clientSecret")));
    }

    private PluginHttpResponse callbackPage(boolean success, String message) {
        String safeMessage = html(message == null ? "授权失败" : message);
        String payload = online.yudream.base.plugin.skin.infrastructure.support.JsonSupport.write(Map.of(
                "type", "yudream-official-bind",
                "success", success,
                "message", message == null ? "授权失败" : message
        )).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        String html = "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<title>正版角色绑定</title><body><p>" + safeMessage + "</p>"
                + "<script>if(window.opener){window.opener.postMessage(" + payload
                + ",window.location.origin)}setTimeout(function(){window.close()},300)</script></body></html>";
        return new PluginHttpResponse(success ? 200 : 400, Map.of(
                "Cache-Control", "no-store, max-age=0",
                "X-Content-Type-Options", "nosniff"
        ), "text/html;charset=UTF-8", html, false);
    }

    private String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String ownerId(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            throw new IllegalStateException("需要站点登录会话");
        }
        return String.valueOf(request.principal().userId());
    }

    private String firstQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String body) {
        return online.yudream.base.plugin.skin.infrastructure.support.JsonSupport.readMap(body);
    }
}
