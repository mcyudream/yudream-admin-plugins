package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.Map;

/**
 * YAP §6.10 GET /v1/events：域事件流（`push` 能力）。全部已加入域的
 * 启动器共享一条事件总线；事件只携带定位元数据，启动器收到后经 GET
 * 拉取真实数据（manifest / 数据信封）。
 */
public class YmclEventsController {

    private final YmclEventBus eventBus;

    public YmclEventsController(YmclEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/events", permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse events(PluginHttpRequest request) {
        return new PluginHttpResponse(
                200,
                Map.of("Cache-Control", "no-cache", "Connection", "keep-alive"),
                "text/event-stream",
                eventBus,
                false);
    }
}
