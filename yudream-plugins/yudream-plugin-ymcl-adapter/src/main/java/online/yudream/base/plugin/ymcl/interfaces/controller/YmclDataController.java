package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.ymcl.api.YmclDataContext;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.ymcl.interfaces.support.PathSegments;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAP §6.6 GET /v1/data/{providerCode}/{sourceCode}：按路由定位贡献方并
 * 拉取数据信封。信封为裸 JSON（wrapResult = false）。
 */
public class YmclDataController {

    private final YmclContributionAggregator aggregator;

    public YmclDataController(YmclContributionAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/data/{providerCode}/{sourceCode}",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse data(PluginHttpRequest request) {
        String providerCode = PathSegments.segment(request.path(), 2);
        String sourceCode = PathSegments.segment(request.path(), 3);
        PluginPrincipal principal = request.principal();
        YmclDataContext context = new YmclDataContext(
                intParam(request, "page", 1),
                intParam(request, "pageSize", 20),
                principal == null ? null : principal.userId(),
                flattenQuery(request));

        return aggregator.findProvider(providerCode, sourceCode)
                .map(provider -> PluginHttpResponse.rawJson(200, provider.fetchData(sourceCode, context)))
                .orElseGet(() -> PluginHttpResponse.rawJson(200,
                        YmclContributionAggregator.emptyEnvelope(1)));
    }

    static int intParam(PluginHttpRequest request, String name, int fallback) {
        var values = request.query().get(name);
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(values.get(0));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** 查询串扁平化（取各参数首值），供 YmclDataContext.query 透传给提供方。 */
    static Map<String, String> flattenQuery(PluginHttpRequest request) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (request.query() == null) {
            return flat;
        }
        request.query().forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                flat.put(name, values.get(0));
            }
        });
        return flat;
    }
}
