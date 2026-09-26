package online.yudream.base.plugin.authlib.application.service;

import online.yudream.base.plugin.authlib.api.PluginAuthlibInfo;
import online.yudream.base.plugin.spi.system.FrameworkServices;

import java.net.URI;
import java.util.Optional;

/**
 * {@link PluginAuthlibInfo} 实现：apiRoot 只从宿主站点配置推导
 * （app.web-url 优先，其次 app.base-url），与请求上下文无关，
 * 供跨插件消费方（mcpanel 等）获取可下发的验证服地址。
 */
public class AuthlibInfoService implements PluginAuthlibInfo {

    public static final String API_LOCATION = "/api/plugins/authlib-injector";
    private static final String APP_WEB_URL_SETTING = "app.web-url";
    private static final String APP_BASE_URL_SETTING = "app.base-url";

    private final FrameworkServices frameworkServices;

    public AuthlibInfoService(FrameworkServices frameworkServices) {
        this.frameworkServices = frameworkServices;
    }

    @Override
    public Optional<String> apiRoot() {
        return configuredOrigin().map(origin -> origin + API_LOCATION);
    }

    /** 站点配置的对外 origin（scheme://host[:port]）；未配置或非法时为空。 */
    public Optional<String> configuredOrigin() {
        return frameworkServices.setting(APP_WEB_URL_SETTING)
                .or(() -> frameworkServices.setting(APP_BASE_URL_SETTING))
                .flatMap(this::originOf);
    }

    private Optional<String> originOf(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            String origin = uri.getScheme() + "://" + uri.getHost();
            if (uri.getPort() >= 0) {
                origin += ":" + uri.getPort();
            }
            return Optional.of(origin);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
