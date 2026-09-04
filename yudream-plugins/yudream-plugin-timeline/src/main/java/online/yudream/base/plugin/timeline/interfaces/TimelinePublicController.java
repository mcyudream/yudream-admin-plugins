package online.yudream.base.plugin.timeline.interfaces;

import java.util.Map;
import online.yudream.base.plugin.timeline.application.TimelineEventService;
import online.yudream.base.plugin.timeline.interfaces.support.HttpSupport;
import online.yudream.base.plugin.timeline.interfaces.support.Views;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 公开端点（/public/**）：匿名可达，只暴露已发布事件。
 * 列表不含 Markdown 详情，详情按 id 单取。
 */
public final class TimelinePublicController {
    private final TimelineEventService eventService;

    public TimelinePublicController(TimelineEventService eventService) {
        this.eventService = eventService;
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/events")
    public PluginHttpResponse events(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records",
                eventService.listPublished().stream().map(Views::summaryView).toList())));
    }

    @PluginHttpEndpoint(method = "GET", path = "/public/events/{id}")
    public PluginHttpResponse event(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                Views.detailView(eventService.requirePublished(HttpSupport.segmentAfter(request.path(), "events")))));
    }
}
