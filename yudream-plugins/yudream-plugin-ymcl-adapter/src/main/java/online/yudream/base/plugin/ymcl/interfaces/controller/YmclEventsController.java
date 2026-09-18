package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.http.PluginSseStream;

import java.util.List;
import java.util.Map;

/**
 * YAP §6.10 GET /v1/events：域事件流（`push` 能力）。全部已加入域的
 * 启动器共享一条事件总线；事件只携带定位元数据，启动器收到后经 GET
 * 拉取真实数据（manifest / 数据信封）。
 *
 * <p>续传：优先读 {@code Last-Event-ID} 请求头，其次 {@code ?lastEventId=}
 * 查询参数（YMCL-Axolotl 当前使用 query）。
 */
public class YmclEventsController {

    private final YmclEventBus eventBus;

    public YmclEventsController(YmclEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PluginHttpEndpoint(method = "GET", path = "/v1/events", permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse events(PluginHttpRequest request) {
        Long afterEventId = resolveLastEventId(request);
        PluginSseStream stream = eventBus.open(afterEventId);
        return new PluginHttpResponse(
                200,
                Map.of(
                        "Cache-Control", "no-cache",
                        "Connection", "keep-alive",
                        "X-Accel-Buffering", "no"),
                "text/event-stream",
                stream,
                false);
    }

    public static Long resolveLastEventId(PluginHttpRequest request) {
        String header = firstHeader(request, "Last-Event-ID");
        if (header == null || header.isBlank()) {
            header = firstHeader(request, "last-event-id");
        }
        Long fromHeader = parseEventId(header);
        if (fromHeader != null) {
            return fromHeader;
        }
        return parseEventId(firstQuery(request, "lastEventId"));
    }

    private static String firstHeader(PluginHttpRequest request, String name) {
        if (request.headers() == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                return values == null || values.isEmpty() ? null : values.get(0);
            }
        }
        return null;
    }

    private static String firstQuery(PluginHttpRequest request, String name) {
        if (request.query() == null) {
            return null;
        }
        List<String> values = request.query().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static Long parseEventId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
