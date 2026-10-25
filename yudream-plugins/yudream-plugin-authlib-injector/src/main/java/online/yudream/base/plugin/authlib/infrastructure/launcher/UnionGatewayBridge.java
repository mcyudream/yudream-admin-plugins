package online.yudream.base.plugin.authlib.infrastructure.launcher;

import online.yudream.base.plugin.authlib.api.PluginFederationGateway;
import online.yudream.base.plugin.union.api.UnionFederationService;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.util.List;
import java.util.Optional;

/**
 * yudream-union 联邦网关桥：全部 yudream-union 类型引用隔离在本类，
 * 仅在 {@code context.dependencyAvailable("yudream-union")} 时实例化/调用
 * （softdepend；union 未启用时本类不加载，宿主行为退化为纯本站）。
 */
public class UnionGatewayBridge implements PluginFederationGateway {

    public static final String UNION_PLUGIN_CODE = "yudream-union";

    private final PluginContext context;

    public UnionGatewayBridge(PluginContext context) {
        this.context = context;
    }

    @Override
    public Optional<RemoteJoinProfile> verifyRemoteJoin(String username, String serverId) {
        return context.service(UNION_PLUGIN_CODE, UnionFederationService.class)
                .flatMap(union -> union.verifyRemoteJoin(username, serverId))
                .map(profile -> new RemoteJoinProfile(profile.uuid(), profile.name(), profile.textureValue()));
    }

    @Override
    public List<String> trustedTextureHosts() {
        return context.service(UNION_PLUGIN_CODE, UnionFederationService.class)
                .map(UnionFederationService::trustedTextureHosts)
                .orElse(List.of());
    }

    @Override
    public List<String> trustedPeerSigningKeysPem() {
        try {
            return context.service(UNION_PLUGIN_CODE, UnionFederationService.class)
                    .map(UnionFederationService::trustedPeerSigningKeysPem)
                    .orElse(List.of());
        } catch (LinkageError e) {
            // 旧版 union（<0.4.0）接口无此方法：视为无受信站钉扎钥
            return List.of();
        }
    }
}
