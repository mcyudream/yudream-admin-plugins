package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.application.service.YmclContributionAggregator;
import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.application.service.YmclP2pLink;
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
 * 服务器档案由已启用插件经 {@code YmclContributionProvider#serverBindings()}
 * 贡献（如 minecraft-server），适配器聚合并合并自身持有的 pack 绑定（发布
 * 控制台推送时写入 documents）。`updatePolicy` 为 YAP 扩展字段（prompt /
 * background / launch-only，YAP §7）。
 */
public class YmclMipController {

    private static final String BINDING_COLLECTION = "ymcl_bindings";

    private final YmclContributionAggregator aggregator;
    private final PluginDocumentStore documents;
    private final YmclEventBus eventBus;
    private final YmclP2pLink p2p;

    public YmclMipController(YmclContributionAggregator aggregator, PluginDocumentStore documents,
                             YmclEventBus eventBus, YmclP2pLink p2p) {
        this.aggregator = aggregator;
        this.documents = documents;
        this.eventBus = eventBus;
        this.p2p = p2p;
    }

    @PluginHttpEndpoint(method = "GET", path = "/mip/api/servers",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse servers(PluginHttpRequest request) {
        List<Map<String, Object>> servers = new ArrayList<>();
        for (Object source : aggregator.serverBindings()) {
            // 契约形态是服务器列表；兼容提供方只给单个档案 Map 的写法
            if (source instanceof List<?> list) {
                for (Object item : list) {
                    appendServer(servers, item);
                }
            } else {
                appendServer(servers, source);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("servers", servers);
        return PluginHttpResponse.rawJson(200, payload);
    }

    private void appendServer(List<Map<String, Object>> servers, Object item) {
        if (!(item instanceof Map<?, ?> serverView)) {
            return;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : serverView.entrySet()) {
            view.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        Object serverId = view.get("serverId");
        if (serverId != null) {
            Map<String, Object> bindingView = bindingView(String.valueOf(serverId));
            if (bindingView != null) {
                view.put("binding", bindingView);
            }
        }
        annotateP2pInstance(p2p, view);
        servers.add(view);
    }

    /**
     * 标注可直连实例（YAP §6.12）：把服务器对外地址交给面板反查（「这条域名由哪台实例提供」），
     * 命中且该实例已开 P2P 时写入 {@code p2pInstanceId}——启动器进服时会静默改走回环隧道，
     * 玩家侧看不到任何变化。未装 mcpanel、地址无匹配、实例未开 P2P 时**不写这个字段**，
     * 服务器保持原有的公网地址语义。
     */
    static void annotateP2pInstance(YmclP2pLink p2p, Map<String, Object> view) {
        if (p2p == null || view.containsKey("p2pInstanceId")) {
            return;
        }
        for (String address : addresses(view)) {
            Optional<String> instanceId = p2p.instanceForAddress(address);
            if (instanceId.isPresent()) {
                view.put("p2pInstanceId", instanceId.get());
                return;
            }
        }
    }

    /** 服务器对外地址候选（主地址 + 各线路地址），逐个反查直到命中。 */
    private static List<String> addresses(Map<String, Object> view) {
        List<String> addresses = new ArrayList<>();
        addAddress(addresses, view.get("mcAddress"));
        if (view.get("endpoints") instanceof List<?> endpoints) {
            for (Object endpoint : endpoints) {
                if (endpoint instanceof Map<?, ?> line) {
                    addAddress(addresses, line.get("address"));
                }
            }
        }
        return addresses;
    }

    private static void addAddress(List<String> addresses, Object value) {
        if (value instanceof String address && !address.isBlank()) {
            addresses.add(address);
        }
    }

    /**
     * 读取服务器当前绑定的 pack（发布控制台推送时写入）。返回 MIP 附录 B
     * binding 形态 + updatePolicy/mcVersion 扩展；无绑定文档的服务器省略
     * binding 节点（MIP §2：解析方忽略缺失字段，NONE 语义）。
     * binding 两种形态：{packId, ...} 整合包要求；{mcVersion} 纯服版本要求
     * （仅要求游戏版本，玩家可用本地实例或下载原版进服）。
     */
    private Map<String, Object> bindingView(String serverId) {
        Optional<Map<String, Object>> doc = documents.findById(BINDING_COLLECTION, serverId);
        if (doc.isEmpty()) {
            return null;
        }
        Map<String, Object> stored = doc.get();
        Map<String, Object> binding = new LinkedHashMap<>();
        Object packId = stored.get("packId");
        if (packId != null && !String.valueOf(packId).isBlank()) {
            binding.put("packId", packId);
        }
        if (stored.get("mcVersion") != null) {
            binding.put("mcVersion", stored.get("mcVersion"));
        }
        binding.put("channel", stored.getOrDefault("channel", "stable"));
        binding.put("pinnedVersion", stored.get("pinnedVersion"));
        binding.put("updatePolicy", stored.getOrDefault("updatePolicy", "prompt"));
        // 原版增强包（玩家可选，YAP §7 三选一）：与必装 packId/mcVersion 并存
        Object optionalPackId = stored.get("optionalPackId");
        if (optionalPackId != null && !String.valueOf(optionalPackId).isBlank()) {
            binding.put("optionalPackId", optionalPackId);
            binding.put("optionalChannel", stored.getOrDefault("optionalChannel", "stable"));
            binding.put("optionalPinnedVersion", stored.get("optionalPinnedVersion"));
        }
        return binding.isEmpty() ? null : binding;
    }

    /**
     * 建立或修改服务器绑定（YAP 附录 B.5：鉴权 + 审计由宿主日志与文档
     * 时间戳承担）。body：packId、mcVersion、channel、pinnedVersion、
     * updatePolicy。packId 与 mcVersion 至少其一——packId = 整合包要求，
     * 仅 mcVersion = 纯服版本要求。
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
        String packId = binding.get("packId") == null ? null : String.valueOf(binding.get("packId")).trim();
        String mcVersion = binding.get("mcVersion") == null ? null : String.valueOf(binding.get("mcVersion")).trim();
        boolean hasPack = packId != null && !packId.isEmpty();
        boolean hasVersion = mcVersion != null && !mcVersion.isEmpty();
        if (!hasPack && !hasVersion) {
            return PluginHttpResponse.rawJson(400, YmclSessionController.error(
                    "invalid_body", "either packId or mcVersion is required"));
        }
        binding.put("serverId", serverId);
        if (!hasPack) {
            binding.remove("packId");
        }
        if (!hasVersion) {
            binding.remove("mcVersion");
        }
        binding.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(BINDING_COLLECTION, serverId, binding);

        eventBus.publish(YmclEventBus.TYPE_BINDING_UPDATED, Map.of(
                "serverId", serverId,
                "packId", String.valueOf(binding.getOrDefault("packId", "")),
                "mcVersion", String.valueOf(binding.getOrDefault("mcVersion", ""))));
        return PluginHttpResponse.rawJson(200, binding);
    }

}
