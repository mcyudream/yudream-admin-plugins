package online.yudream.base.plugin.launcher.interfaces.controller;

import online.yudream.base.plugin.launcher.application.service.AuthSessionAppService;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.Map;

/**
 * 协议 v2 认证端点（§2）。一期只做方式发现与规范化会话；
 * 账密/刷新/OAuth 交换直连宿主公开端点，device-code 与 external 为二期。
 */
public class AuthV2Controller {

    private final AuthSessionAppService authSessionAppService;

    public AuthV2Controller(AuthSessionAppService authSessionAppService) {
        this.authSessionAppService = authSessionAppService;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v2/auth/methods", wrapResult = false)
    public PluginHttpResponse methods(PluginHttpRequest request) {
        return PluginHttpResponse.rawJson(200, authSessionAppService.methodsView());
    }

    @PluginHttpEndpoint(method = "GET", path = "/v2/auth/session", wrapResult = false)
    public PluginHttpResponse session(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            return PluginHttpResponse.rawJson(401, Map.of("message", "未登录或令牌已失效"));
        }
        return PluginHttpResponse.rawJson(200, authSessionAppService.sessionView(request.principal()));
    }
}
