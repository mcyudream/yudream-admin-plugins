package online.yudream.plugin.worldmap.interfaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.plugin.worldmap.bootstrap.WorldMapPlugin;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.worldmap.application.cmd.CreateMapCmd;
import online.yudream.plugin.worldmap.application.service.MapAppService;
import online.yudream.plugin.worldmap.application.service.WorldMapEventStream;

import java.util.Map;

/**
 * 管理接口（需 plugin:world-map:manage 权限，由端点注解声明）。
 */
public class AdminMapController {

    private final MapAppService mapAppService;
    private final WorldMapEventStream eventStream;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminMapController(MapAppService mapAppService, WorldMapEventStream eventStream) {
        this.mapAppService = mapAppService;
        this.eventStream = eventStream;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps", permission = WorldMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(Map.of("maps", mapAppService.listAdmin()));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/maps", permission = WorldMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        requireMethod(request, "POST");
        try {
            JsonNode body = objectMapper.readTree(request.body() == null || request.body().isBlank()
                    ? "{}" : request.body());
            CreateMapCmd cmd = new CreateMapCmd(
                    text(body, "name"),
                    text(body, "dimension"),
                    text(body, "worldFileId"),
                    text(body, "clientJarFileId"),
                    body.hasNonNull("stripNetherCeiling") ? body.get("stripNetherCeiling").asBoolean() : null
            );
            return PluginHttpResponse.ok(mapAppService.create(cmd));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体解析失败：" + e.getMessage());
        }
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/maps/{mapId}/render", permission = WorldMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse render(PluginHttpRequest request) {
        requireMethod(request, "POST");
        String mapId = pathSegment(request.path(), 2);
        return PluginHttpResponse.ok(mapAppService.render(mapId));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/maps/{mapId}", permission = WorldMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        requireMethod(request, "DELETE");
        String mapId = pathSegment(request.path(), 2);
        mapAppService.delete(mapId);
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/tasks", permission = WorldMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse tasks(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(Map.of("tasks", mapAppService.tasks()));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/tasks/{taskId}/cancel", permission = WorldMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse cancelTask(PluginHttpRequest request) {
        requireMethod(request, "POST");
        String taskId = pathSegment(request.path(), 2);
        mapAppService.cancelTask(taskId);
        return PluginHttpResponse.ok(Map.of("canceled", true));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/tasks/events", permission = WorldMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse taskEvents(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return new PluginHttpResponse(200, Map.of(), "text/event-stream", eventStream, false);
    }

    private String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private void requireMethod(PluginHttpRequest request, String method) {
        if (!method.equalsIgnoreCase(request.method())) {
            throw new IllegalArgumentException("仅支持 " + method + " 请求");
        }
    }

    /**
     * 取路径段：/admin/maps/{id}/... 中 {id} 为 index 2。
     */
    private String pathSegment(String path, int index) {
        String[] segments = path.split("/");
        if (segments.length <= index + 1 || segments[index + 1].isBlank()) {
            throw new IllegalArgumentException("路径参数缺失");
        }
        return segments[index + 1];
    }
}
