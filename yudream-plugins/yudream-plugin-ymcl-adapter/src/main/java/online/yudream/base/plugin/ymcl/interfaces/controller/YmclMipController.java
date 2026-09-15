package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.ymcl.interfaces.support.PathSegments;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * YAP 附录 B /mip/api/servers：服务器绑定聚合。
 *
 * 服务器档案来自 minecraft-server 插件（跨插件服务
 * {@code PluginMinecraftService}）；pack 绑定由本适配器持有（发布控制台
 * 推送时写入 documents）。`updatePolicy` 为 YAP 扩展字段（prompt /
 * background / launch-only，YAP §7）。
 */
public class YmclMipController {

    private static final String BINDING_COLLECTION = "ymcl_bindings";

    private final YmclContributionAggregator aggregator;
    private final PluginDocumentStore documents;
    private final YmclEventBus eventBus;

    public YmclMipController(YmclContributionAggregator aggregator, PluginDocumentStore documents, YmclEventBus eventBus) {
        this.aggregator = aggregator;
        this.documents = documents;
        this.eventBus = eventBus;
    }

    @PluginHttpEndpoint(method = "GET", path = "/mip/api/servers",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse servers(PluginHttpRequest request) {
        List<Map<String, Object>> servers = new ArrayList<>();
        for (Object source : aggregator.serverBindings()) {
            if (source instanceof Map<?, ?> serverView) {
                Map<String, Object> view = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : serverView.entrySet()) {
                    view.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                Map<String, Object> bindingView = bindingView(String.valueOf(view.get("serverId")));
                if (bindingView != null) {
                    view.put("binding", bindingView);
                }
                servers.add(view);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("servers", servers);
        return PluginHttpResponse.rawJson(200, payload);
    }

    /**
     * 读取服务器当前绑定的 pack（发布控制台推送时写入）。返回 MIP 附录 B
     * binding 形态 + updatePolicy 扩展；无绑定的服务器省略 binding 节点
     * （MIP §2：解析方忽略缺失字段，NONE 语义）。
     */
    private Map<String, Object> bindingView(String serverId) {
        Optional<Map<String, Object>> doc = documents.findById(BINDING_COLLECTION, serverId);
        if (doc.isEmpty()) {
            return null;
        }
        Map<String, Object> stored = doc.get();
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("packId", stored.get("packId"));
        binding.put("channel", stored.getOrDefault("channel", "stable"));
        binding.put("pinnedVersion", stored.get("pinnedVersion"));
        binding.put("updatePolicy", stored.getOrDefault("updatePolicy", "prompt"));
        return binding;
    }

    /**
     * 建立或修改服务器绑定（YAP 附录 B.5：鉴权 + 审计由宿主日志与文档
     * 时间戳承担）。body：packId、channel、pinnedVersion、updatePolicy。
     */
    @PluginHttpEndpoint(method = "PUT", path = "/mip/api/servers/{serverId}/binding",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse putBinding(PluginHttpRequest request) {
        String serverId = PathSegments.segment(request.path(), 3);
        Map<String, Object> binding;
        try {
            binding = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(request.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception error) {
            return PluginHttpResponse.rawJson(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON binding"));
        }
        if (binding.get("packId") == null) {
            return PluginHttpResponse.rawJson(400, YmclSessionController.error(
                    "invalid_body", "packId is required"));
        }
        binding.put("serverId", serverId);
        binding.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(BINDING_COLLECTION, serverId, binding);

        eventBus.publish(YmclEventBus.TYPE_BINDING_UPDATED, Map.of(
                "serverId", serverId,
                "packId", String.valueOf(binding.get("packId"))));
        return PluginHttpResponse.rawJson(200, binding);
    }

}
