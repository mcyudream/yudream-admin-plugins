package online.yudream.plugin.worldmap.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.worldmap.application.service.MapAppService;
import online.yudream.plugin.worldmap.application.service.WorldMapLayerService;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;

import java.util.Map;
import java.util.Optional;

/**
 * 公开地图接口（匿名可访问）：地图列表、查看器设置、tile 与贴图集。
 * 端点不声明 permission，未登录访客也可访问。
 */
public class PublicMapController {

    private static final String CACHE_HEADER = "public, max-age=31536000, immutable";

    private final MapAppService mapAppService;
    private final TileStorage tileStorage;
    private final WorldMapLayerService layers;

    public PublicMapController(MapAppService mapAppService, TileStorage tileStorage, WorldMapLayerService layers) {
        this.mapAppService = mapAppService;
        this.tileStorage = tileStorage;
        this.layers = layers;
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
        return hiresResponse(mapId, activeGeneration(mapId), tx, tz);
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
        return tileResponse(tileStorage.lowres(mapId, activeGeneration(mapId), lod, tx, tz), "image/png", false);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/textures/atlas.png")
    public PluginHttpResponse atlas(PluginHttpRequest request) {
        requireGet(request);
        String mapId = segment(request.path(), 1);
        return tileResponse(tileStorage.atlas(mapId, activeGeneration(mapId)), "image/png", false);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/generations/{generationId}/tiles/hires/{tx}/{tz}")
    public PluginHttpResponse generationHiresTile(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = request.path().split("/");
        return hiresResponse(segments[2], segments[4], Integer.parseInt(segments[7]), Integer.parseInt(segments[8]));
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/generations/{generationId}/tiles/lowres/{lod}/{tx}/{tz}")
    public PluginHttpResponse generationLowresTile(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = request.path().split("/");
        return tileResponse(tileStorage.lowres(segments[2], segments[4], Integer.parseInt(segments[7]),
                Integer.parseInt(segments[8]), Integer.parseInt(segments[9])), "image/png", false);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/generations/{generationId}/textures/atlas.png")
    public PluginHttpResponse generationAtlas(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = request.path().split("/");
        return tileResponse(tileStorage.atlas(segments[2], segments[4]), "image/png", false);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/generations/{generationId}/textures.json")
    public PluginHttpResponse generationBlueMapTextures(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = request.path().split("/");
        return tileResponse(tileStorage.blueMapTextures(segments[2], segments[4]), "application/json", false);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/generations/{generationId}/settings.json")
    public PluginHttpResponse generationBlueMapSettings(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = request.path().split("/");
        return tileResponse(tileStorage.blueMapSettings(segments[2], segments[4]), "application/json", false);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/generations/{generationId}/lowres-index.json")
    public PluginHttpResponse generationBlueMapLowresIndex(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = request.path().split("/");
        return tileResponse(tileStorage.blueMapLowresIndex(segments[2], segments[4]), "application/json", false);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/markers")
    public PluginHttpResponse markers(PluginHttpRequest request) {
        requireGet(request);
        return PluginHttpResponse.ok(Map.of("markerSets", layers.markerSets(segment(request.path(), 1))));
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

    private PluginHttpResponse hiresResponse(String mapId, String generationId, int tx, int tz) {
        Optional<online.yudream.base.plugin.spi.system.storage.PluginStoredFile> binary = tileStorage.blueMapHires(mapId, generationId, tx, tz);
        if (binary.isPresent()) {
            return tileResponse(binary, "application/octet-stream", false);
        }
        return tileResponse(tileStorage.hires(mapId, generationId, tx, tz), "application/json", true);
    }

    private void requireGet(PluginHttpRequest request) {
        if (!"GET".equalsIgnoreCase(request.method())) {
            throw new IllegalArgumentException("仅支持 GET 请求");
        }
    }

    private String activeGeneration(String mapId) {
        return mapAppService.requireReady(mapId).getActiveGenerationId();
    }

    private String segment(String path, int index) {
        String[] segments = path.split("/");
        if (segments.length <= index + 1 || segments[index + 1].isBlank()) {
            throw new IllegalArgumentException("路径参数缺失");
        }
        return segments[index + 1];
    }
}
