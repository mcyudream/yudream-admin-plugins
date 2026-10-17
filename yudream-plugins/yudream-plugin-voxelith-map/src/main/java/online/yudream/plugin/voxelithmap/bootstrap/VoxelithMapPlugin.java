package online.yudream.plugin.voxelithmap.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import java.util.Optional;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.voxelithmap.application.service.GenerationPublisher;
import online.yudream.plugin.voxelithmap.application.service.PluginJobService;
import online.yudream.plugin.voxelithmap.application.service.MapProjectAppService;
import online.yudream.plugin.voxelithmap.application.service.PanoramaService;
import online.yudream.plugin.voxelithmap.application.service.PreviewService;
import online.yudream.plugin.voxelithmap.application.service.RenderOrchestrator;
import online.yudream.plugin.voxelithmap.application.service.ShardWorkerService;
import online.yudream.plugin.voxelithmap.application.service.VoxelithEventStream;
import online.yudream.plugin.voxelithmap.domain.repo.MapProjectRepo;
import online.yudream.plugin.voxelithmap.domain.repo.RenderTaskRepo;
import online.yudream.plugin.voxelithmap.infrastructure.engine.PlatformFileMaterializer;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithCliRenderEngine;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRenderEngine;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfigResolver;
import online.yudream.plugin.voxelithmap.infrastructure.repository.DocumentMapProjectRepo;
import online.yudream.plugin.voxelithmap.infrastructure.repository.DocumentRenderTaskRepo;
import online.yudream.plugin.voxelithmap.infrastructure.sidecar.VoxelithSidecar;
import online.yudream.plugin.voxelithmap.infrastructure.sidecar.VoxelithSidecarClient;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;
import online.yudream.plugin.voxelithmap.interfaces.controller.AdminVoxelithMapController;
import online.yudream.plugin.voxelithmap.interfaces.controller.PublicVoxelithMapController;

import java.util.Map;

/**
 * 体素世界地图插件入口。
 *
 * <p>基于 <b>Voxelith Map Core</b> 渲染核：插件负责编排（存档编目、范围框选、任务排队、
 * 进度推送、原子发布、对象存储、公开瓦片服务），渲染与二维预览由 Voxelith 完成。
 * Voxelith 以**只读、零改动**方式使用——引擎 fat jar 随插件打包，运行时解出并校验哈希，
 * 渲染走 {@code RenderMapCli} 子进程，预览走 {@code voxelith-server} 边车，全部通过
 * 命令行参数与已发布 REST 契约对接（见 THIRD-PARTY-NOTICES/Voxelith-MIT.txt）。</p>
 *
 * <p>入口类只做装配：不写业务流程、不做 IO、不起外部连接（编排器与边车都在
 * 第一次真正用到时才启动）。</p>
 */
@PluginSpec(
        code = VoxelithMapPlugin.PLUGIN_CODE,
        name = "体素世界地图",
        version = VoxelithMapPlugin.VERSION,
        description = "把 Minecraft 世界存档渲染为可自由浏览的三维地图，支持框选范围、任务进度推送、标注与全景图",
        icon = "i-ri:earth-line")
@PluginPermission(
        code = VoxelithMapPlugin.MANAGE_PERMISSION,
        name = "管理体素世界地图",
        module = "体素世界地图",
        description = "上传存档、框选范围、触发渲染、编辑标注与全景图")
@PluginFrontend(
        moduleName = "voxelithMap",
        menuTitle = "体素世界地图",
        menuIcon = "i-ri:earth-line",
        menuSort = 92,
        styles = {"style.css"},
        routes = {
                // 全部页面统一挂在 /platform/plugins/{code}/ 下（本仓其它插件的既有约定），两条原因：
                // ① 页面不能再落在某个公开路由的子树里。宿主守卫在「已登录 + notFound」时按前缀匹配
                //    (to.path.startsWith(公开路径 + '/')) 注册公开路由并重进当前路径；公开查看器原先就在
                //    /voxelith-map，会把 /voxelith-map/admin/** 一并吞下，硬刷新时反复重入 → vue-router
                //    以 Infinite redirect 中止启动，页面表现为「刷新无响应/白屏」。现在没有路由占用
                //    /voxelith-map 这个前缀，该类问题从根上不再成立。
                // ② 路由 name 是宿主菜单行的注册键（pluginRegistrationKey = route:{module}:{name}），
                //    而菜单投影只创建、不更新、不删除：某个 name 一旦换给另一个页面，旧行会永远占着
                //    这个键，新页面再也注册不出来（2.1.3 就因此出现「两个地图列表、任务中心丢失」）。
                //    因此每个页面的 name 必须全新且稳定——历史上用过 -admin-* 系列，这里统一为 -page-*。
                // 两个查看入口、同一个组件、两种形态（组件按路径前缀区分，见 Viewer 的 topMenuLayout）：
                // ① 站点页 /map：公开站导航入口（siteNav），套宿主页眉页脚，功能条保持纵向；
                // ② 平台页 /platform/.../viewer：不套页眉页脚、占满视口（fullscreen），功能条横排顶部。
                // 分工的理由：站内浏览要一个"内容页"（有导航可回、有页脚），嵌入/投屏/专注看图要"纯地图"；
                // 两者共用一份实现，不必维护两个查看器。
                @PluginRoute(path = "/map", name = "voxelith-map-page-map",
                        title = "世界地图", component = "voxelith-map/Viewer",
                        publicAccess = true, siteNav = true, sort = 1),
                // 平台页不再声明 siteNav：站点导航只留 /map 一个入口（fullscreen 与 siteNav 同时声明时
                // 前者生效，留着标志只会让导航多出一条指向"无页眉页脚页"的项）。
                @PluginRoute(path = "/platform/plugins/voxelith-map/viewer", name = "voxelith-map-page-viewer",
                        title = "体素世界地图", component = "voxelith-map/Viewer",
                        publicAccess = true, fullscreen = true, sort = 1),
                @PluginRoute(path = "/platform/plugins/voxelith-map/maps", name = "voxelith-map-page-maps",
                        title = "地图管理", component = "voxelith-map/MapList",
                        permission = VoxelithMapPlugin.MANAGE_PERMISSION, sort = 2),
                @PluginRoute(path = "/platform/plugins/voxelith-map/gallery", name = "voxelith-map-page-gallery",
                        title = "地图列表", component = "voxelith-map/MapGallery",
                        permission = VoxelithMapPlugin.MANAGE_PERMISSION, sort = 3),
                @PluginRoute(path = "/platform/plugins/voxelith-map/map-detail", name = "voxelith-map-page-map-detail",
                        title = "地图详情", component = "voxelith-map/MapDetail",
                        permission = VoxelithMapPlugin.MANAGE_PERMISSION, hideInMenu = true, sort = 4),
                @PluginRoute(path = "/platform/plugins/voxelith-map/markers", name = "voxelith-map-page-markers",
                        title = "标注管理", component = "voxelith-map/MapMarkers",
                        permission = VoxelithMapPlugin.MANAGE_PERMISSION, hideInMenu = true, sort = 5),
                @PluginRoute(path = "/platform/plugins/voxelith-map/archived", name = "voxelith-map-page-archived",
                        title = "归档", component = "voxelith-map/MapArchived",
                        permission = VoxelithMapPlugin.MANAGE_PERMISSION, hideInMenu = true, sort = 6),
                @PluginRoute(path = "/platform/plugins/voxelith-map/tasks", name = "voxelith-map-page-tasks",
                        title = "任务中心", component = "voxelith-map/MapTasks",
                        permission = VoxelithMapPlugin.MANAGE_PERMISSION, sort = 7),
        })
public class VoxelithMapPlugin implements YuDreamPlugin {

    public static final String PLUGIN_CODE = "voxelith-map";
    /**
     * 插件版本。必须与 plugin.yml 的 version 保持一致（WorldArchiveExtractorTest 同级的
     * PluginVersionConsistencyTest 在构建期守护这一点）；/health 与 @PluginSpec 都读它。
     */
    public static final String VERSION = "2.1.71";
    public static final String MANAGE_PERMISSION = "plugin:voxelith-map:manage";

    /** 运行期句柄：卸载时按相反顺序释放。 */
    private RenderOrchestrator orchestrator;
    private VoxelithSidecar sidecar;

    @Override
    public void onEnable(PluginContext context) {
        PluginDocumentStore documents = context.documents();
        MapProjectRepo projectRepo = new DocumentMapProjectRepo(documents);
        RenderTaskRepo taskRepo = new DocumentRenderTaskRepo(documents);
        MapArtifactStorage storage = new MapArtifactStorage(context.files());
        ObjectMapper objectMapper = new ObjectMapper();

        VoxelithRuntimeConfig config = new VoxelithRuntimeConfigResolver(context.framework()).resolve();
        GenerationPublisher publisher = new GenerationPublisher(storage);
        VoxelithEventStream eventStream = new VoxelithEventStream();
        VoxelithRenderEngine engine = new VoxelithCliRenderEngine(config,
                VoxelithCliRenderEngine.defaultAccessor(config));

        var files = context.files();
        var materializer = new online.yudream.plugin.voxelithmap.infrastructure.engine.PlatformFileMaterializer(
                context.framework(), files);
        var taskLogs = new online.yudream.plugin.voxelithmap.infrastructure.storage.TaskLogStore(config);
        orchestrator = new RenderOrchestrator(projectRepo, taskRepo, storage, publisher, engine, config,
                materializer, eventStream, objectMapper,
                new online.yudream.plugin.voxelithmap.application.service.ClientJarLibrary(context.documents()),
                taskLogs);
        // 完整日志目录按保留期清理：渲染任务多，日志文件会一直堆着
        taskLogs.prune(java.time.Duration.ofDays(30));

        sidecar = new VoxelithSidecar(config);
        VoxelithSidecarClient sidecarClient = new VoxelithSidecarClient(sidecar, objectMapper);
        PreviewService previewService = new PreviewService(storage, sidecar, sidecarClient, config, materializer);
        PanoramaService panoramaService = new PanoramaService(projectRepo, storage, engine, config);
        ShardWorkerService shardWorkerService = new ShardWorkerService(projectRepo, storage, engine, config, materializer);

        MapProjectAppService appService = new MapProjectAppService(projectRepo, storage, publisher,
                orchestrator, previewService, sidecar, config, materializer);

        // 长任务作业（删除地图 / 实例导入）：放到后台执行，进度与日志经任务流推送。
        // 实例文件的读取经 InstanceFilePort 适配器（契约按类名反射查找，不形成编译期依赖；
        // 当前面板 0.22.6 没有契约时报告不可用，导入自动退回浏览器搬运），因此 mcpanel 是
        // softdepend：本地开发不装面板也能完整启用本插件。
        java.util.concurrent.ExecutorService jobExecutor =
                java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "voxelith-import");
                    thread.setDaemon(true);
                    return thread;
                });
        var instanceFiles =
                new online.yudream.plugin.voxelithmap.infrastructure.mcpanel.McpanelInstanceFileAdapter(context);
        PluginJobService jobs = new PluginJobService(appService, taskRepo, eventStream, jobExecutor,
                instanceFiles, files, config, materializer,
                new online.yudream.plugin.voxelithmap.infrastructure.modpack.ModPackInspector(objectMapper));

        context.registerHttpController(new PublicVoxelithMapController(appService, storage));
        context.registerHttpController(new AdminVoxelithMapController(appService, eventStream,
                panoramaService, shardWorkerService, objectMapper,
                new online.yudream.plugin.voxelithmap.application.service.ClientJarLibrary(context.documents()),
                jobs, taskLogs));

        // 重启自愈：上一次进程留下的未完成任务标记失败并回收它们占的代目录
        try {
            orchestrator.recoverStaleTasks();
        } catch (RuntimeException ignored) {
            // 自愈失败不影响后续使用：残留任务在管理页可见，可由管理员手工清理
        }

        context.onDispose(orchestrator);
        context.onDispose(jobExecutor::shutdownNow);
        context.onDispose(sidecar::stop);
        // 全景只是单次子进程，关掉线程池即可；worker 是长驻进程，必须先停掉并回传产物
        context.onDispose(panoramaService);
        context.onDispose(shardWorkerService);
    }

    @Override
    public void onDisable(PluginContext context) {
        // onDispose 注册的回调已覆盖编排器与边车；这里显式停一次边车，避免 disable 后残留进程
        if (sidecar != null) {
            sidecar.stop();
        }
    }

    @PluginHttpEndpoint(method = "GET", path = "/health")
    public PluginHttpResponse health() {
        return PluginHttpResponse.ok(Map.of(
                "status", "UP",
                "plugin", PLUGIN_CODE,
                "version", VERSION));
    }
}
