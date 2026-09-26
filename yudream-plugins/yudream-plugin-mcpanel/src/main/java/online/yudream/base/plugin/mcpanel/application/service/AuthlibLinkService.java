package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.authlib.api.PluginAuthlibInfo;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * authlib-injector 联动端口：读取本站 Yggdrasil 验证服对外 API 根，
 * 免去设置页手填 apiRoot。
 *
 * <p>提供方缺失（softdepend 未装/未启用）、站点未配置对外地址或提供方版本过旧
 * （LinkageError，旧版无该契约）时一律返回 empty，设置页降级为手填——
 * 与 MinecraftLinkService 同一降级语义：可选集成绝不影响面板主体可用。
 */
public class AuthlibLinkService {

    public static final String PROVIDER_CODE = "authlib-injector";

    private final Supplier<Optional<PluginAuthlibInfo>> provider;

    public AuthlibLinkService(Supplier<Optional<PluginAuthlibInfo>> provider) {
        this.provider = provider;
    }

    public boolean available() {
        return apiRoot().isPresent();
    }

    /** 本站验证服 API 根（如 https://mc.example.com/api/plugins/authlib-injector）。 */
    public Optional<String> apiRoot() {
        try {
            return provider.get().flatMap(PluginAuthlibInfo::apiRoot);
        } catch (RuntimeException | LinkageError error) {
            return Optional.empty();
        }
    }
}
