package online.yudream.base.plugin.ymcl.api;

import java.util.List;
import java.util.Map;

/**
 * YAP §3 扩展点：其他 yda 插件经
 * {@code context.registerExtension(YmclContributionProvider.class, impl)}
 * 向 ymcl-adapter 贡献域页面、数据源与 server 动作；适配器在 manifest 与
 * data/action 端点上聚合全部提供方。
 *
 * 本接口位于 ymcl-adapter 的稳定 {@code *.api} 包，消费方以 provided 依赖
 * 编译；ymcl-adapter 缺失时消费方按 softdepend 降级（捕获 LinkageError）。
 */
public interface YmclContributionProvider {

    /** 提供方代码，构成数据路由前缀 /v1/data/{providerCode}/{sourceCode}。 */
    String providerCode();

    /** 贡献的页面集合（renderer + dataSource + requiredPermission）。 */
    List<YmclPageDescriptor> pages();

    /** 贡献的数据源集合（sourceCode + schemaVersion + cacheTtl）。 */
    List<YmclDataSourceDescriptor> dataSources();

    /**
     * 拉取数据信封（YAP §6.6）：返回可 JSON 化的 Map，至少包含
     * {@code records}（List）、{@code actions}（页面级动作）、
     * {@code itemActions}（条目级动作）与 {@code total}。动作是声明式
     * 数据（kind + params 模板），由启动器通用执行。
     */
    Object fetchData(String sourceCode, YmclDataContext context);

    /**
     * 执行 server 动作（YAP §6.7，可选能力）。默认不支持。
     */
    default Object executeAction(String actionCode, Map<String, Object> params, YmclDataContext context) {
        throw new UnsupportedOperationException(
                "provider " + providerCode() + " does not execute actions");
    }

    /**
     * 服务器绑定数据（YAP 附录 B /mip/api/servers 的聚合来源，可选）。
     * 返回可 JSON 化的服务器列表（serverId/name/mcAddress/status/
     * currentSeason{...}），无服务器档案能力的提供方返回 null。
     */
    default Object serverBindings() {
        return null;
    }

    /**
     * 随 jar 携带的页面 bundle（YAP §6.8，可选能力）。module/extension
     * 渲染器的页面在此声明 zip 字节，由适配器按
     * /v1/bundles/{bundleId}/{version}/package.zip 统一下发；打包与
     * bundle 块构建用 {@link YmclModuleSupport}。
     */
    default List<YmclBundleContribution> bundles() {
        return List.of();
    }
}
