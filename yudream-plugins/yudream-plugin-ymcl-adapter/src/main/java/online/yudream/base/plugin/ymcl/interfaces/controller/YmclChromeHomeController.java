package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * YAP §6.5 chrome/home（home 能力，管理端）：域首页卡片布局的读取与保存。
 *
 * - GET（view 权限）：读取当前配置，供启动器设计器加载；
 * - PUT（design 权限）：保存配置，适配器持久化到宿主文档存储并广播
 *   {@code manifest.updated}（YAP §6.10）——成员下次拉取 manifest 生效。
 */
public class YmclChromeHomeController {

    private static final String COLLECTION = "ymcl_chrome_home";
    private static final String DOC_ID = "home";

    private final PluginDocumentStore documents;
    private final YmclEventBus eventBus;

    public YmclChromeHomeController(PluginDocumentStore documents, YmclEventBus eventBus) {
        this.documents = documents;
        this.eventBus = eventBus;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/chrome/home", permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse get(PluginHttpRequest request) {
        Optional<Map<String, Object>> config = documents.findById(COLLECTION, DOC_ID);
        if (config.isEmpty()) {
            // Empty layout: the launcher treats this as "no home hosting".
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("schemaVersion", 1);
            empty.put("locked", false);
            empty.put("cards", java.util.List.of());
            return PluginHttpResponse.rawJson(200, empty);
        }
        return PluginHttpResponse.rawJson(200, config.get());
    }

    @PluginHttpEndpoint(method = "PUT", path = "/v1/chrome/home", permission = YmclAdapterPlugin.DESIGN_PERMISSION)
    public PluginHttpResponse put(PluginHttpRequest request) {
        Map<String, Object> config;
        try {
            config = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(request.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception error) {
            return PluginHttpResponse.rawJson(400, YmclSessionController.error(
                    "invalid_body", "Request body must be a JSON home config"));
        }
        if (!Boolean.TRUE.equals(config.get("locked")) && !Boolean.FALSE.equals(config.get("locked"))) {
            config.put("locked", Boolean.FALSE);
        }
        config.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(COLLECTION, DOC_ID, config);

        eventBus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "chrome.home"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        return PluginHttpResponse.rawJson(200, result);
    }
}
