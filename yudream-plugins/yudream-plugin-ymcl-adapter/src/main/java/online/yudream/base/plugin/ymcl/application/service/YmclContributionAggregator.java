package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.ymcl.api.YmclBundleContribution;
import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.api.YmclDataContext;
import online.yudream.base.plugin.ymcl.api.YmclDataSourceDescriptor;
import online.yudream.base.plugin.ymcl.api.YmclPageDescriptor;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 聚合全部 {@link YmclContributionProvider} 扩展点实现，供 manifest 拼装与
 * data/action 路由消费（YAP §3）。每次请求动态查询 extensions——提供方
 * 的启停即时反映，无需重启适配器。
 */
public class YmclContributionAggregator {

    private final PluginContext context;

    public YmclContributionAggregator(PluginContext context) {
        this.context = context;
    }

    public List<YmclContributionProvider> providers() {
        return context.extensions(YmclContributionProvider.class);
    }

    public List<YmclPageDescriptor> pages() {
        List<YmclPageDescriptor> pages = new ArrayList<>();
        for (YmclContributionProvider provider : providers()) {
            pages.addAll(provider.pages());
        }
        return pages;
    }

    public List<YmclDataSourceDescriptor> dataSources() {
        List<YmclDataSourceDescriptor> sources = new ArrayList<>();
        for (YmclContributionProvider provider : providers()) {
            sources.addAll(provider.dataSources());
        }
        return sources;
    }

    /** 服务器绑定数据：返回实现了 serverBindings() 的提供方结果列表。 */
    public List<Object> serverBindings() {
        List<Object> bindings = new ArrayList<>();
        for (YmclContributionProvider provider : providers()) {
            Object bindings_source = provider.serverBindings();
            if (bindings_source != null) {
                bindings.add(bindings_source);
            }
        }
        return bindings;
    }

    /** 提供方随 jar 贡献的页面 bundle（YAP §6.8），按 bundleId@version 定位。 */
    public Optional<YmclBundleContribution> findBundle(String bundleId, String version) {
        for (YmclContributionProvider provider : providers()) {
            for (YmclBundleContribution bundle : provider.bundles()) {
                if (bundle.bundleId().equals(bundleId) && bundle.version().equals(version)) {
                    return Optional.of(bundle);
                }
            }
        }
        return Optional.empty();
    }

    /** 按数据端点路由（providerCode + sourceCode）定位提供方。 */
    public Optional<YmclContributionProvider> findProvider(String providerCode, String sourceCode) {
        return providers().stream()
                .filter(provider -> provider.providerCode().equals(providerCode))
                .filter(provider -> provider.dataSources().stream()
                        .anyMatch(source -> source.sourceCode().equals(sourceCode)))
                .findFirst();
    }

    /**
     * 按动作端点路由（providerCode）定位提供方。动作没有独立声明表——
     * actionCode 由提供方 executeAction 内部解释（未知动作返回 toast 信封），
     * 因此这里只按 providerCode 匹配。此前复用 findProvider 拿 actionCode
     * 去匹配数据源码，任何动作都恒 404。
     */
    public Optional<YmclContributionProvider> findActionProvider(String providerCode) {
        return providers().stream()
                .filter(provider -> provider.providerCode().equals(providerCode))
                .findFirst();
    }

    /** 构建数据端点的"来源未知"信封（providerCode 或 sourceCode 不匹配）。 */
    public static Map<String, Object> emptyEnvelope(int schemaVersion) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", schemaVersion);
        envelope.put("records", List.of());
        envelope.put("total", 0);
        envelope.put("actions", List.of());
        envelope.put("itemActions", List.of());
        return envelope;
    }
}
