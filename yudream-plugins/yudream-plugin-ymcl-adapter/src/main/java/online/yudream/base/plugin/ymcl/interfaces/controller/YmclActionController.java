package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.api.YmclDataContext;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.ymcl.interfaces.support.PathSegments;
import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;

import java.util.Map;

/**
 * YAP §6.7 POST /v1/action/{providerCode}/{actionCode}：server 动作的服务
 * 端执行。动作 body 为渲染后的参数 Map；提供方内部负责细化权限。
 */
public class YmclActionController {

    private final YmclContributionAggregator aggregator;

    public YmclActionController(YmclContributionAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @PluginHttpEndpoint(method = "POST", path = "/v1/action/{providerCode}/{actionCode}",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse action(PluginHttpRequest request) {
        String providerCode = PathSegments.segment(request.path(), 2);
        String actionCode = PathSegments.segment(request.path(), 3);
        PluginPrincipal principal = request.principal();
        YmclDataContext context = new YmclDataContext(1, 20,
                principal == null ? null : principal.userId(),
                YmclDataController.flattenQuery(request));
        Map<String, Object> params = parseParams(request.body());
        return aggregator.findActionProvider(providerCode)
                .map(provider -> execute(provider, actionCode, params, context))
                .orElseGet(() -> PluginHttpResponse.rawJson(404,
                        YmclSessionController.error("action_not_found",
                                "No provider executes " + providerCode + "/" + actionCode)));
    }

    /** 提供方未实现动作能力（SPI 默认方法）时按动作不存在处理，而非 500。 */
    private PluginHttpResponse execute(YmclContributionProvider provider, String actionCode,
                                       Map<String, Object> params, YmclDataContext context) {
        try {
            return PluginHttpResponse.rawJson(200,
                    provider.executeAction(actionCode, params, context));
        } catch (UnsupportedOperationException unsupported) {
            return PluginHttpResponse.rawJson(404,
                    YmclSessionController.error("action_not_found",
                            "Provider " + provider.providerCode() + " executes no actions"));
        }
    }

    private Map<String, Object> parseParams(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
