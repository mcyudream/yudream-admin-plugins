package online.yudream.base.plugin.timeline.interfaces;

import java.util.Map;
import online.yudream.base.plugin.timeline.application.PageResult;
import online.yudream.base.plugin.timeline.application.TimelineEventPayload;
import online.yudream.base.plugin.timeline.application.TimelineEventService;
import online.yudream.base.plugin.timeline.bootstrap.TimelinePlugin;
import online.yudream.base.plugin.timeline.domain.TimelineEvent;
import online.yudream.base.plugin.timeline.infrastructure.JsonSupport;
import online.yudream.base.plugin.timeline.interfaces.support.HttpSupport;
import online.yudream.base.plugin.timeline.interfaces.support.Views;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 管理端事件接口（/admin/**）：跨用户的事件维护，全部端点要求 MANAGE 权限。
 * 公开数据只经 /public/** 暴露，本控制器不承担公开查询。
 */
public final class TimelineAdminController {
    private final TimelineEventService eventService;
    private final JsonSupport json;

    public TimelineAdminController(TimelineEventService eventService, JsonSupport json) {
        this.eventService = eventService;
        this.json = json;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/events", permission = TimelinePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            PageResult<TimelineEvent> result = eventService.queryAdmin(
                    HttpSupport.first(request, "keyword"), HttpSupport.first(request, "status"),
                    HttpSupport.pageParam(request), HttpSupport.sizeParam(request, 20));
            return PluginHttpResponse.ok(Map.of(
                    "records", result.records().stream().map(Views::summaryView).toList(),
                    "total", result.total()));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/events", permission = TimelinePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            TimelineEventPayload payload = json.read(request.body(), TimelineEventPayload.class);
            return PluginHttpResponse.ok(Views.detailView(eventService.create(payload)));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/events/{id}", permission = TimelinePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                Views.detailView(eventService.require(HttpSupport.segmentAfter(request.path(), "events")))));
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/events/{id}", permission = TimelinePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse update(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            TimelineEventPayload payload = json.read(request.body(), TimelineEventPayload.class);
            return PluginHttpResponse.ok(Views.detailView(
                    eventService.update(HttpSupport.segmentAfter(request.path(), "events"), payload)));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/events/{id}", permission = TimelinePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            eventService.delete(HttpSupport.segmentAfter(request.path(), "events"));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/events/{id}/publish", permission = TimelinePlugin.MANAGE_PERMISSION)
    public PluginHttpResponse publish(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            boolean published = json.readTree(request.body()).path("published").asBoolean(true);
            return PluginHttpResponse.ok(Views.detailView(
                    eventService.setPublished(HttpSupport.segmentAfter(request.path(), "events"), published)));
        });
    }
}
