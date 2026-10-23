package online.yudream.base.plugin.interop.infrastructure.support;

import online.yudream.base.plugin.interop.domain.aggregate.SiteIdentityRecord;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.union.api.UnionFederationService;

import java.util.Optional;

/**
 * yudream-union 身份桥：全部 union 类型引用隔离在本类，仅在
 * {@code context.dependencyAvailable("yudream-union")} 时实例化/调用
 * （softdepend；union 未启用时返回空，mc-interop 回退自身设置）。
 */
public class UnionIdentityBridge {

    public static final String UNION_PLUGIN_CODE = "yudream-union";

    private final PluginContext context;

    public UnionIdentityBridge(PluginContext context) {
        this.context = context;
    }

    /** 静态入口：bootstrap 以 Supplier 引用，union 在位时才实际加载本类。 */
    public static SiteIdentityRecord identityOf(PluginContext context) {
        return new UnionIdentityBridge(context).identity().orElse(null);
    }

    /** union 站点身份（站点身份页配置）；未配置 siteCode 时为空。 */
    public Optional<SiteIdentityRecord> identity() {
        return context.service(UNION_PLUGIN_CODE, UnionFederationService.class)
                .flatMap(UnionFederationService::siteIdentity)
                .map(identity -> new SiteIdentityRecord(
                        identity.code(), identity.name(), identity.origin()));
    }
}
