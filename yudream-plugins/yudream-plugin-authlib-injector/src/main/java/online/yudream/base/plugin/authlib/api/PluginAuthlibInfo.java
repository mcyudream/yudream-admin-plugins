package online.yudream.base.plugin.authlib.api;

import java.util.Optional;

/**
 * authlib-injector 对外只读信息契约：向其他插件（如 mcpanel 注入链路）
 * 提供本站 Yggdrasil 验证服务的对外 API 根地址，免去消费方手填。
 *
 * <p>apiRoot 由宿主站点配置（app.web-url / app.base-url）推导，
 * 不依赖任何 HTTP 请求上下文；站点未配置对外地址时返回 empty，
 * 消费方应降级为手填。
 */
public interface PluginAuthlibInfo {

    /**
     * 本站 Yggdrasil API 根（如 {@code https://mc.example.com/api/plugins/authlib-injector}）。
     * 站点未配置对外 web-url/base-url 时为空。
     */
    Optional<String> apiRoot();
}
