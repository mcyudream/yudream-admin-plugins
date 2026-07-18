package online.yudream.plugin.worldmap.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.worldmap.application.service.MapAppService;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;

import java.util.Map;
import java.util.Optional;

/**
 * 公开地图接口（匿名可访问）：地图列表、查看器设置、tile 与贴图集。
 * 端点不声明 permission，未登录访客也可访问。
 */
public class PublicMapController {

    private static final String CACHE_HEADER = "public, max-age=86400";

    private final MapAppService mapAppService;
    private final TileStorage tileStorage;

    public PublicMapController(MapAppService mapAppService, TileStorage tileStorage) {
        this.mapAppService = mapAppService;
        this.tileStorage = tileStorage;
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps")
    public PluginHttpResponse maps(PluginHttpRequest request) {
        requireGet(request);
        return PluginHttpResponse.ok(Map.of("maps", mapAppService.listPublic()));
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/settings")
    public PluginHttpResponse settings(PluginHttpRequest request) {
        requireGet(request);
        String mapId = segment(request.path(), 1);
        return PluginHttpResponse.ok(mapAppService.settings(mapId));
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/tiles/hires/{tx}/{tz}")
    public PluginHttpResponse hiresTile(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = request.path().split("/");
        // /maps/{id}/tiles/hires/{tx}/{tz}
        String mapId = segments[2];
        int tx = Integer.parseInt(segments[5]);
        int tz = Integer.parseInt(segments[6]);
        return tileResponse(tileStorage.hires(mapId, tx, tz), "application/json", true);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/tiles/lowres/{lod}/{tx}/{tz}")
    public PluginHttpResponse lowresTile(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = request.path().split("/");
        // /maps/{id}/tiles/lowres/{lod}/{tx}/{tz}
        String mapId = segments[2];
        int lod = Integer.parseInt(segments[5]);
        int tx = Integer.parseInt(segments[6]);
        int tz = Integer.parseInt(segments[7]);
        return tileResponse(tileStorage.lowres(mapId, lod, tx, tz), "image/png", false);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/textures/atlas.png")
    public PluginHttpResponse atlas(PluginHttpRequest request) {
        requireGet(request);
        String mapId = segment(request.path(), 1);
        return tileResponse(tileStorage.atlas(mapId), "image/png", false);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/markers")
    public PluginHttpResponse markers(PluginHttpRequest request) {
        requireGet(request);
        return PluginHttpResponse.ok(Map.of("markerSets", java.util.List.of()));
    }

    private PluginHttpResponse tileResponse(Optional<online.yudream.base.plugin.spi.system.storage.PluginStoredFile> file,
                                            String contentType, boolean gzip) {
        if (file.isEmpty()) {
            return PluginHttpResponse.rawJson(404, Map.of("message", "tile not found"));
        }
        try (var input = file.get().inputStream()) {
            byte[] body = input.readAllBytes();
            java.util.Map<String, String> headers = gzip
                    ? Map.of("Content-Encoding", "gzip", "Cache-Control", CACHE_HEADER)
                    : Map.of("Cache-Control", CACHE_HEADER);
            return new PluginHttpResponse(200, headers, contentType, body, false);
        } catch (Exception e) {
            return PluginHttpResponse.rawJson(500, Map.of("message", "tile 读取失败"));
        }
    }

    private void requireGet(PluginHttpRequest request) {
        if (!"GET".equalsIgnoreCase(request.method())) {
            throw new IllegalArgumentException("仅支持 GET 请求");
        }
    }

    private String segment(String path, int index) {
        String[] segments = path.split("/");
        if (segments.length <= index + 1 || segments[index + 1].isBlank()) {
            throw new IllegalArgumentException("路径参数缺失");
        }
        return segments[index + 1];
    }
}
