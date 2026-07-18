package online.yudream.plugin.worldmap.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.worldmap.application.service.MapAppService;
import online.yudream.plugin.worldmap.application.service.RenderOrchestrator;
import online.yudream.plugin.worldmap.application.service.WorldMapEventStream;
import online.yudream.plugin.worldmap.domain.repo.MapInstanceRepo;
import online.yudream.plugin.worldmap.domain.repo.RenderTaskRepo;
import online.yudream.plugin.worldmap.infrastructure.render.DefaultWorldMapRenderer;
import online.yudream.plugin.worldmap.infrastructure.render.WorldMapRenderer;
import online.yudream.plugin.worldmap.infrastructure.repository.DocumentMapInstanceRepo;
import online.yudream.plugin.worldmap.infrastructure.repository.DocumentRenderTaskRepo;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;
import online.yudream.plugin.worldmap.interfaces.controller.AdminMapController;
import online.yudream.plugin.worldmap.interfaces.controller.PublicMapController;

import java.util.Map;

/**
 * 世界地图插件入口：Minecraft 存档 3D 地图渲染与浏览。
 */
@PluginSpec(
        code = WorldMapPlugin.PLUGIN_CODE,
        name = "世界地图",
        version = "1.0.0",
        description = "Minecraft 世界存档 3D 地图渲染与浏览（对标 BlueMap），支持原版资产渲染、前端标注与地点介绍扩展"
)
@PluginPermission(
        code = WorldMapPlugin.MANAGE_PERMISSION,
        name = "管理世界地图",
        module = "世界地图",
        description = "管理地图实例、触发渲染任务与维护地点标注"
)
@PluginFrontend(
        moduleName = "worldMap",
        menuTitle = "世界地图",
        menuIcon = "i-ri:map-2-line",
        menuSort = 90,
        routes = {
                @PluginRoute(
                        path = "/world-map",
                        name = "world-map-viewer",
                        title = "世界地图",
                        component = "world-map/Viewer",
                        publicAccess = true,
                        sort = 1
                ),
                @PluginRoute(
                        path = "/world-map/admin/maps",
                        name = "world-map-admin-maps",
                        title = "地图管理",
                        component = "world-map/admin/MapList",
                        permission = WorldMapPlugin.MANAGE_PERMISSION,
                        sort = 2
                ),
                @PluginRoute(
                        path = "/world-map/admin/map-detail",
                        name = "world-map-admin-map-detail",
                        title = "地图详情",
                        component = "world-map/admin/MapDetail",
                        permission = WorldMapPlugin.MANAGE_PERMISSION,
                        hideInMenu = true,
                        sort = 3
                )
        }
)
public class WorldMapPlugin implements YuDreamPlugin {

    public static final String PLUGIN_CODE = "world-map";
    public static final String MANAGE_PERMISSION = "plugin:world-map:manage";

    @Override
    public void onEnable(PluginContext context) {
        PluginDocumentStore documents = context.documents();
        MapInstanceRepo mapRepo = new DocumentMapInstanceRepo(documents);
        RenderTaskRepo taskRepo = new DocumentRenderTaskRepo(documents);
        TileStorage tileStorage = new TileStorage(context.files());
        WorldMapEventStream eventStream = new WorldMapEventStream();
        WorldMapRenderer renderer = new DefaultWorldMapRenderer();
        RenderOrchestrator orchestrator = new RenderOrchestrator(
                taskRepo, mapRepo, tileStorage, context.framework(), renderer, eventStream);
        MapAppService mapAppService = new MapAppService(
                mapRepo, taskRepo, tileStorage, context.framework(), orchestrator);
        context.registerHttpController(new PublicMapController(mapAppService, tileStorage));
        context.registerHttpController(new AdminMapController(mapAppService, eventStream));
        context.onDispose(orchestrator);
    }

    @PluginHttpEndpoint(method = "GET", path = "/health")
    public PluginHttpResponse health() {
        return PluginHttpResponse.ok(Map.of("status", "UP", "plugin", PLUGIN_CODE));
    }
}
