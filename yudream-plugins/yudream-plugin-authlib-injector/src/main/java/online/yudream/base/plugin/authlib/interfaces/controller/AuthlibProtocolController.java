package online.yudream.base.plugin.authlib.interfaces.controller;

import online.yudream.base.plugin.authlib.interfaces.http.AuthlibHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class AuthlibProtocolController {
    private final AuthlibHttpFacade http;

    public AuthlibProtocolController(AuthlibHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/", wrapResult = false)
    public PluginHttpResponse metadata(PluginHttpRequest request) { return http.metadata(request); }

    @PluginHttpEndpoint(method = "POST", path = "/authserver/authenticate", wrapResult = false)
    public PluginHttpResponse authenticate(PluginHttpRequest request) { return http.authenticate(request); }

    @PluginHttpEndpoint(method = "POST", path = "/authserver/refresh", wrapResult = false)
    public PluginHttpResponse refresh(PluginHttpRequest request) { return http.refresh(request); }

    @PluginHttpEndpoint(method = "POST", path = "/authserver/validate", wrapResult = false)
    public PluginHttpResponse validate(PluginHttpRequest request) { return http.validate(request); }

    @PluginHttpEndpoint(method = "POST", path = "/authserver/invalidate", wrapResult = false)
    public PluginHttpResponse invalidate(PluginHttpRequest request) { return http.invalidate(request); }

    @PluginHttpEndpoint(method = "POST", path = "/authserver/signout", wrapResult = false)
    public PluginHttpResponse signout(PluginHttpRequest request) { return http.signout(request); }

    @PluginHttpEndpoint(method = "POST", path = "/sessionserver/session/minecraft/join", wrapResult = false)
    public PluginHttpResponse join(PluginHttpRequest request) { return http.join(request); }

    @PluginHttpEndpoint(method = "GET", path = "/sessionserver/session/minecraft/hasJoined", wrapResult = false)
    public PluginHttpResponse hasJoined(PluginHttpRequest request) { return http.hasJoined(request); }

    @PluginHttpEndpoint(method = "GET", path = "/sessionserver/session/minecraft/profile/{uuid}", wrapResult = false)
    public PluginHttpResponse profile(PluginHttpRequest request) { return http.profile(request); }

    @PluginHttpEndpoint(method = "POST", path = "/api/profiles/minecraft", wrapResult = false)
    public PluginHttpResponse profiles(PluginHttpRequest request) { return http.profiles(request); }

    @PluginHttpEndpoint(method = "PUT", path = "/api/user/profile/{uuid}/{textureType}", wrapResult = false)
    public PluginHttpResponse setTexture(PluginHttpRequest request) { return http.setTexture(request); }

    @PluginHttpEndpoint(method = "DELETE", path = "/api/user/profile/{uuid}/{textureType}", wrapResult = false)
    public PluginHttpResponse clearTexture(PluginHttpRequest request) { return http.clearTexture(request); }
}
