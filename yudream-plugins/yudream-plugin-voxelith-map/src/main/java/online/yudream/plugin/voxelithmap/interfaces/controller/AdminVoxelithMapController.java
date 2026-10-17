package online.yudream.plugin.voxelithmap.interfaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.voxelithmap.application.cmd.CreateMapCmd;
import online.yudream.plugin.voxelithmap.application.cmd.ImportInstanceJobCmd;
import online.yudream.plugin.voxelithmap.application.cmd.UpdateMapCmd;
import online.yudream.plugin.voxelithmap.application.service.ClientJarLibrary;
import online.yudream.plugin.voxelithmap.application.service.PluginJobService;
import online.yudream.plugin.voxelithmap.application.service.MapProjectAppService;
import online.yudream.plugin.voxelithmap.application.service.PanoramaService;
import online.yudream.plugin.voxelithmap.application.service.ShardWorkerService;
import online.yudream.plugin.voxelithmap.application.service.VoxelithEventStream;
import online.yudream.plugin.voxelithmap.bootstrap.VoxelithMapPlugin;
import online.yudream.plugin.voxelithmap.domain.value.DefaultView;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;
import online.yudream.plugin.voxelithmap.infrastructure.world.WorldArchiveExtractor;

import java.io.IOException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 管理端接口（全部要求 {@code plugin:voxelith-map:manage}）。
 *
 * <p>请求体一律走 Jackson {@code JsonNode} 手工解析：宿主对未知字段是宽松的，
 * 手工解析能给出准确的中文错误（「缺少 worldFileId」而不是一个泛化的 400）。
 * 所有校验失败统一抛 {@link IllegalArgumentException}，由宿主映射成 400 + 消息。</p>
 *
 * <p>路径参数一律经 {@link RequestPaths#segment} 取：宿主给的 path 未做百分号解码，
 * 中文 id 直取会变成 {@code %E4%B8%87...}（详见该类注释）。</p>
 */
public class AdminVoxelithMapController {

    private static final String NO_CACHE = "no-cache";

    private final MapProjectAppService maps;
    private final VoxelithEventStream eventStream;
    private final PanoramaService panorama;
    private final ShardWorkerService shardWorker;
    private final ObjectMapper objectMapper;

    private final ClientJarLibrary clientJars;
    private final PluginJobService jobs;
    /** 完整任务日志（任务文档里只有尾部 4000 字） */
    private final online.yudream.plugin.voxelithmap.infrastructure.storage.TaskLogStore taskLogs;

    public AdminVoxelithMapController(MapProjectAppService maps, VoxelithEventStream eventStream,
                                      PanoramaService panorama, ShardWorkerService shardWorker,
                                      ObjectMapper objectMapper, ClientJarLibrary clientJars,
                                      PluginJobService jobs,
                                      online.yudream.plugin.voxelithmap.infrastructure.storage.TaskLogStore taskLogs) {
        this.maps = maps;
        this.eventStream = eventStream;
        this.panorama = panorama;
        this.shardWorker = shardWorker;
        this.objectMapper = objectMapper;
        this.clientJars = clientJars;
        this.jobs = jobs;
        this.taskLogs = taskLogs;
    }

    // ------------------------------------------------------------------
    // 地图管理
    // ------------------------------------------------------------------

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps", permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(Map.of("maps", maps.listAdmin()));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}", permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(maps.detail(RequestPaths.segment(request.path(), 2)));
    }

    /**
     * 同一世界的各维度地图（渲染页的「切换维度」）。
     *
     * <p>与公开端同一语义，但**含未渲染与已归档**的维度：渲染页正是要看到「哪些维度还没渲染」
     * 才能逐个切过去渲染，把未发布的藏起来会让这个入口失去意义。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}/dimensions",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse dimensions(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(Map.of("maps",
                maps.listAdminDimensions(RequestPaths.segment(request.path(), 2))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/maps", permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        requireMethod(request, "POST");
        JsonNode body = body(request);
        CreateMapCmd cmd = new CreateMapCmd(
                text(body, "name"),
                text(body, "worldFileId"),
                text(body, "worldName"),
                text(body, "clientJarFileId"),
                text(body, "clientJarName"),
                text(body, "modsPackFileId"),
                text(body, "modsPackName"),
                modJars(body.path("modJars")),
                text(body, "dimension"),
                settings(body.path("settings"), null),
                // 省略 = 只建 dimension 指定的那一张；true = 按存档包内的并列维度一次建全
                body.path("allDimensions").asBoolean(false) ? Boolean.TRUE : Boolean.FALSE);
        return PluginHttpResponse.ok(maps.create(cmd));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/maps/{mapId}", permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse update(PluginHttpRequest request) {
        requireMethod(request, "POST");
        String mapId = RequestPaths.segment(request.path(), 2);
        JsonNode body = body(request);
        UpdateMapCmd cmd = new UpdateMapCmd(
                text(body, "name"),
                text(body, "worldFileId"),
                text(body, "worldName"),
                text(body, "clientJarFileId"),
                text(body, "clientJarName"),
                body.hasNonNull("modsPackFileId") ? body.path("modsPackFileId").asText("") : null,
                text(body, "modsPackName"),
                body.has("modJars") && !body.path("modJars").isMissingNode() && !body.path("modJars").isNull()
                        ? modJars(body.path("modJars")) : null,
                settings(body.path("settings"), null),
                integer(body, "regionMinX"),
                integer(body, "regionMinZ"),
                integer(body, "regionMaxX"),
                integer(body, "regionMaxZ"),
                body.path("clearRegion").asBoolean(false));
        return PluginHttpResponse.ok(maps.update(mapId, cmd));
    }

    /** 归档：从管理列表与公开查看器隐藏（数据保留），可在归档页恢复或彻底删除。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/maps/{mapId}/archive",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse archiveMap(PluginHttpRequest request) {
        requireMethod(request, "POST");
        String mapId = RequestPaths.segment(request.path(), 2);
        return PluginHttpResponse.ok(maps.archive(mapId));
    }

    /** 恢复已归档地图。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/maps/{mapId}/unarchive",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse unarchiveMap(PluginHttpRequest request) {
        requireMethod(request, "POST");
        String mapId = RequestPaths.segment(request.path(), 2);
        return PluginHttpResponse.ok(maps.unarchive(mapId));
    }

    /** 已归档地图列表。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/archived-maps",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse archivedMaps(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(Map.of("maps", maps.listArchived()));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/maps/{mapId}",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        requireMethod(request, "DELETE");
        String mapId = RequestPaths.segment(request.path(), 2);
        maps.delete(mapId);
        return PluginHttpResponse.ok(Map.of("deleted", true, "mapId", mapId));
    }

    /**
     * 保存默认初始视角（管理员在查看页把当前机位「设为默认视角」）。
     *
     * <p>请求体 {@code {x,y,z,tx,ty,tz}} 为相机位置与注视点的世界方块坐标；
     * 可选 {@code coverPngBase64}：当前画面的 PNG 截图（base64），保存后作为列表页展示图。</p>
     */
    @PluginHttpEndpoint(method = "PUT", path = "/admin/maps/{mapId}/default-view",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveDefaultView(PluginHttpRequest request) {
        requireMethod(request, "PUT");
        String mapId = RequestPaths.segment(request.path(), 2);
        JsonNode body = body(request);
        DefaultView view = new DefaultView(
                body.path("x").asDouble(),
                body.path("y").asDouble(),
                body.path("z").asDouble(),
                body.path("tx").asDouble(),
                body.path("ty").asDouble(),
                body.path("tz").asDouble());
        byte[] cover = null;
        JsonNode coverNode = body.get("coverPngBase64");
        if (coverNode != null && !coverNode.isNull() && !coverNode.asText().isBlank()) {
            try {
                cover = Base64.getDecoder().decode(coverNode.asText());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("封面截图不是合法的 base64 图片数据");
            }
        }
        maps.setDefaultView(mapId, view, cover);
        return PluginHttpResponse.ok(Map.of("saved", true));
    }

    /** 清除默认初始视角（查看页回到内置机位；已保存的封面保留）。 */
    @PluginHttpEndpoint(method = "DELETE", path = "/admin/maps/{mapId}/default-view",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse clearDefaultView(PluginHttpRequest request) {
        requireMethod(request, "DELETE");
        String mapId = RequestPaths.segment(request.path(), 2);
        maps.clearDefaultView(mapId);
        return PluginHttpResponse.ok(Map.of("cleared", true));
    }

    // ------------------------------------------------------------------
    // 原版客户端 jar 库：预上传各版本 jar，新建地图时按版本直接选用
    // ------------------------------------------------------------------

    @PluginHttpEndpoint(method = "GET", path = "/admin/client-jars",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse listClientJars(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(Map.of("jars", clientJars.list()));
    }

    /** 新增/覆盖一个版本：{@code {version, fileId, fileName?, sizeBytes?}}（文件先经宿主文件上传接口上传）。 */
    @PluginHttpEndpoint(method = "PUT", path = "/admin/client-jars",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse putClientJar(PluginHttpRequest request) {
        requireMethod(request, "PUT");
        JsonNode body = body(request);
        ClientJarLibrary.Entry entry = clientJars.put(
                text(body, "version"),
                text(body, "fileId"),
                body.path("fileName").asText(""),
                body.path("sizeBytes").asLong(0));
        return PluginHttpResponse.ok(Map.of(
                "version", entry.version(),
                "fileId", entry.fileId(),
                "fileName", entry.fileName(),
                "sizeBytes", entry.sizeBytes()));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/client-jars/{version}",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteClientJar(PluginHttpRequest request) {
        requireMethod(request, "DELETE");
        String version = RequestPaths.segment(request.path(), 2);
        boolean removed = clientJars.remove(version);
        return PluginHttpResponse.ok(Map.of("deleted", removed, "version", version));
    }

    /**
     * 实例导入能力探测：服务端能否直接读取面板实例文件。
     *
     * <p>为 true 时导入走服务端作业（关页不中断）；为 false 时（面板插件未装/未启用，或旧版面板
     * 没有实例文件访问契约）自动降级为浏览器搬运——面板插件不需要为导入做任何升级。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/admin/instance-import-capability",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse instanceImportCapability(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(Map.of("serverImport", jobs.instanceImportAvailable()));
    }

    /**
     * 发起一次**浏览器搬运**的导入任务（同步端点，见 {@link #importInstanceWorld}）。
     *
     * <p>面板插件没有实例文件访问契约时用它：任务先在任务中心登记，页面搬运并把进展回报进来，
     * 存档上传完成后由 {@code .../complete} 交给服务端建图。</p>
     */
    @PluginHttpEndpoint(method = "POST", path = "/admin/import-instance-jobs/browser",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse startBrowserImportInstanceJob(PluginHttpRequest request) {
        requireMethod(request, "POST");
        return PluginHttpResponse.ok(jobs.startBrowserImport(importCmd(body(request))));
    }

    /** 页面回报搬运进度（阶段 / 百分比 / 明细）；已取消或已结束的任务会被忽略。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/import-instance-jobs/{taskId}/progress",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse reportImportProgress(PluginHttpRequest request) {
        requireMethod(request, "POST");
        JsonNode body = body(request);
        jobs.reportBrowserImportProgress(RequestPaths.segment(request.path(), 2),
                body.path("stage").asText(""), body.path("percent").asInt(0), body.path("detail").asText(""));
        return PluginHttpResponse.ok(Map.of("ok", true));
    }

    /** 页面把上传完成的存档（与可选模组包）交给服务端，由服务端接管建图。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/import-instance-jobs/{taskId}/complete",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse completeImportJob(PluginHttpRequest request) {
        requireMethod(request, "POST");
        JsonNode body = body(request);
        jobs.completeBrowserImport(RequestPaths.segment(request.path(), 2), importCmd(body),
                body.path("worldFileId").asText(""), body.path("modsFileId").asText(""),
                body.path("modsPackName").asText(""));
        return PluginHttpResponse.ok(Map.of("ok", true));
    }

    /** 页面报告导入失败（面板报错、上传失败、用户取消搬运等）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/import-instance-jobs/{taskId}/fail",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse failImportJob(PluginHttpRequest request) {
        requireMethod(request, "POST");
        JsonNode body = body(request);
        jobs.failBrowserImport(RequestPaths.segment(request.path(), 2), body.path("message").asText(""));
        return PluginHttpResponse.ok(Map.of("ok", true));
    }

    /** 导入任务入参（服务端作业与浏览器搬运共用一套字段）。 */
    private static ImportInstanceJobCmd importCmd(JsonNode body) {
        // 来源实例 id 必须由前端带上：详情页「同步最新存档」靠它回面板重新拉取。
        // 早前这里写死过 "mcpanel"，导致记录下来的来源实例根本不存在、同步必然报「实例已不存在」
        List<String> worldDirs = new ArrayList<>();
        JsonNode dirs = body.path("worldDirs");
        if (dirs.isArray()) {
            dirs.forEach(dir -> {
                String value = dir.asText("");
                if (!value.isBlank()) {
                    worldDirs.add(value);
                }
            });
        }
        return new ImportInstanceJobCmd(
                body.path("instanceId").asText(""),
                body.path("instanceName").asText(""),
                body.path("name").asText(""),
                body.path("version").asText(""),
                body.path("withMods").asBoolean(true),
                worldDirs);
    }

    /**
     * 发起一次实例导入作业（服务端后台执行）：面板侧打包 world（与可选 mods）→ 分块拉回本插件
     * 文件存储 → 建图。
     *
     * <p>body 只带实例 id 与少量元数据：数据搬运全程在插件进程内完成，浏览器不参与，因此刷新或
     * 关闭页面都不会中断导入；进度、日志与取消都在任务中心。面板插件没有实例文件访问契约时改走
     * {@code /admin/import-instance-jobs/browser}（浏览器搬运，同样登记成任务）。</p>
     */
    @PluginHttpEndpoint(method = "POST", path = "/admin/import-instance-jobs",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse startImportInstanceJob(PluginHttpRequest request) {
        requireMethod(request, "POST");
        return PluginHttpResponse.ok(jobs.startImport(importCmd(body(request))));
    }


    /**
     * 发起一次删除作业（服务端后台执行）：回收渲染产物、存档与地图记录。
     *
     * <p>归档仍是即时操作；这里是「彻底删除」，因此放后台执行并可在任务中心看到结果与日志。</p>
     */
    @PluginHttpEndpoint(method = "POST", path = "/admin/maps/{mapId}/delete-jobs",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse startDeleteJob(PluginHttpRequest request) {
        requireMethod(request, "POST");
        String mapId = RequestPaths.segment(request.path(), 2);
        return PluginHttpResponse.ok(jobs.startDelete(mapId));
    }

    /**
     * 解析模组包，列出包内所有模组 jar。
     *
     * <p>只读 zip 中央目录、不解压；但宿主 SPI 是顺序流、zip 中央目录在文件末尾，所以服务端必须先把包
     * 物化到本地——这是一次完整的对象读，因此由管理页**按需**触发（点「解析包内 jar」），不在页面加载时跑。</p>
     */
    @PluginHttpEndpoint(method = "POST", path = "/admin/mod-packs/inspect",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse inspectModPack(PluginHttpRequest request) {
        requireMethod(request, "POST");
        JsonNode body = body(request);
        String fileId = body.path("fileId").asText("");
        if (fileId.isBlank()) {
            throw new IllegalArgumentException("缺少 fileId（模组包文件 id）");
        }
        return PluginHttpResponse.ok(maps.inspectModPack(fileId, body.path("name").asText("")));
    }

    /**
     * 读取某个任务的**完整日志**。
     *
     * <p>任务文档只保留日志尾部（4000 字，够看失败原因与最后阶段），因为渲染日志动辄几万行
     * （每批一行）。子进程的完整输出写在任务临时目录里，任务收尾时搬到持久目录，这里读出来给需要
     * 排查的人：text/plain，浏览器直接显示，也可另存。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/admin/tasks/{taskId}/log",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse taskLog(PluginHttpRequest request) {
        requireMethod(request, "GET");
        String taskId = RequestPaths.segment(request.path(), 2);
        java.util.Optional<java.nio.file.Path> file = taskLogs.find(taskId);
        if (file.isEmpty()) {
            throw new IllegalArgumentException("该任务没有保留完整日志（可能是较早的任务，或已过保留期）");
        }
        try {
            byte[] body = java.nio.file.Files.readAllBytes(file.get());
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("Cache-Control", NO_CACHE);
            return new PluginHttpResponse(200, headers, "text/plain; charset=utf-8", body, false);
        }
        catch (IOException e) {
            throw new IllegalStateException("读取完整日志失败：" + e.getMessage());
        }
    }

    /** 下载地图当前存档（zip）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}/world.zip",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse downloadWorldZip(PluginHttpRequest request) {
        requireMethod(request, "GET");
        String mapId = RequestPaths.segment(request.path(), 2);
        byte[] zip;
        try {
            zip = maps.worldArchiveZip(mapId);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("读取存档失败：" + e.getMessage());
        }
        if (zip == null || zip.length == 0) {
            throw new IllegalArgumentException("该地图还没有可下载的存档");
        }
        String encoded = java.util.Base64.getEncoder().encodeToString(zip);
        return PluginHttpResponse.ok(Map.of(
                "zipBase64", encoded,
                "name", mapId + "-world.zip"));
    }

    /** 用已上传的新存档覆盖地图存档（实例同步最新文件；body 为平台文件 id）。 */
    @PluginHttpEndpoint(method = "PUT", path = "/admin/maps/{mapId}/world.zip",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse uploadWorldZip(PluginHttpRequest request) {
        requireMethod(request, "PUT");
        String mapId = RequestPaths.segment(request.path(), 2);
        JsonNode body = body(request);
        String worldFileId = body.path("worldFileId").asText("");
        if (worldFileId.isBlank()) {
            throw new IllegalArgumentException("缺少 worldFileId（新存档上传后的平台文件 id）");
        }
        maps.replaceWorldArchive(mapId, worldFileId);
        return PluginHttpResponse.ok(Map.of("replaced", true));
    }

    // ------------------------------------------------------------------
    // 渲染任务
    // ------------------------------------------------------------------

    @PluginHttpEndpoint(method = "POST", path = "/admin/maps/{mapId}/render",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse render(PluginHttpRequest request) {
        requireMethod(request, "POST");
        return PluginHttpResponse.ok(maps.render(RequestPaths.segment(request.path(), 2)));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/tasks", permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse tasks(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(Map.of("tasks", maps.tasks()));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}/tasks",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse tasksOf(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(Map.of("tasks", maps.tasksOf(RequestPaths.segment(request.path(), 2))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/tasks/{taskId}/cancel",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse cancelTask(PluginHttpRequest request) {
        requireMethod(request, "POST");
        String taskId = RequestPaths.segment(request.path(), 2);
        boolean canceled = maps.cancelTask(taskId) || jobs.cancel(taskId);
        return PluginHttpResponse.ok(Map.of("canceled", canceled));
    }

    /** 任务进度事件流。前端用 fetch 流式读取（宿主令牌在 header 上，EventSource 带不上）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/tasks/events",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse taskEvents(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return new PluginHttpResponse(200, Map.of(), "text/event-stream", eventStream, false);
    }

    // ------------------------------------------------------------------
    // 框选底图
    // ------------------------------------------------------------------

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}/preview",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse preview(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(maps.preview(RequestPaths.segment(request.path(), 2)));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/maps/{mapId}/preview",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse triggerPreview(PluginHttpRequest request) {
        requireMethod(request, "POST");
        return PluginHttpResponse.ok(maps.triggerPreview(RequestPaths.segment(request.path(), 2)));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}/preview.png",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse previewImage(PluginHttpRequest request) {
        requireMethod(request, "GET");
        String mapId = RequestPaths.segment(request.path(), 2);
        Optional<Integer> step = queryInt(request, "step");
        try {
            Optional<byte[]> image = maps.previewImage(mapId, step.orElse(null));
            if (image.isEmpty()) {
                return new PluginHttpResponse(404, Map.of("Cache-Control", NO_CACHE),
                        "application/json", "{\"message\":\"底图尚未生成\"}", false);
            }
            return new PluginHttpResponse(200, Map.of("Cache-Control", NO_CACHE),
                    "image/png", image.get(), false);
        } catch (IOException e) {
            throw new IllegalArgumentException("底图获取失败：" + e.getMessage());
        }
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}/window.png",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse previewWindow(PluginHttpRequest request) {
        requireMethod(request, "GET");
        String mapId = RequestPaths.segment(request.path(), 2);
        int minX = queryInt(request, "minX").orElseThrow(() -> new IllegalArgumentException("缺少 minX"));
        int minZ = queryInt(request, "minZ").orElseThrow(() -> new IllegalArgumentException("缺少 minZ"));
        int size = clamp(queryInt(request, "size").orElse(512), 16, 1024);
        int step = Math.max(1, queryInt(request, "step").orElse(1));
        try {
            return maps.previewWindow(mapId, minX, minZ, size, step)
                    .map(image -> new PluginHttpResponse(200, Map.of("Cache-Control", NO_CACHE),
                            "image/png", image, false))
                    .orElseGet(() -> new PluginHttpResponse(404, Map.of("Cache-Control", NO_CACHE),
                            "application/json", "{\"message\":\"窗口图不可用\"}", false));
        } catch (IOException e) {
            throw new IllegalArgumentException("窗口图获取失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 标注
    // ------------------------------------------------------------------

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}/markers",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse markers(PluginHttpRequest request) {
        requireMethod(request, "GET");
        // wrapped 必须为 true：本端点经 sdk.http（宿主 axios）消费，拦截器要求
        // {code:200,...} 包装形态，裸 JSON 会被判为业务失败并全局弹「请求失败」。
        // （区别于公开 markers.json——那个由 viewer 直接 fetch 消费，保持裸形态。）
        // maps.markers() 返回文件原文 String，必须先 readTree 成对象再进 body，
        // 否则包装后 data 是「JSON 字符串」而非对象，前端 res.sets 拿不到内容。
        String mapId = RequestPaths.segment(request.path(), 2);
        try {
            return new PluginHttpResponse(200, Map.of("Cache-Control", NO_CACHE),
                    "application/json", objectMapper.readTree(maps.markers(mapId)), true);
        } catch (IOException e) {
            throw new IllegalArgumentException("标注文件解析失败：" + e.getMessage());
        }
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/maps/{mapId}/markers",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveMarkers(PluginHttpRequest request) {
        requireMethod(request, "PUT");
        String mapId = RequestPaths.segment(request.path(), 2);
        String raw = request.body();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        maps.saveMarkers(mapId, raw);
        return PluginHttpResponse.ok(Map.of("saved", true));
    }

    // ------------------------------------------------------------------
    // 全景图（服务端预烘焙的透视全景，客户端只需加载一张 PNG）
    // ------------------------------------------------------------------

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}/panorama",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse panoramaStatus(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(panorama.status(RequestPaths.segment(request.path(), 2)));
    }

    /**
     * 触发全景烘焙。必须先渲染过一次——全景读的是渲染留下的地表栅格与高度场。
     */
    @PluginHttpEndpoint(method = "POST", path = "/admin/maps/{mapId}/panorama",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse triggerPanorama(PluginHttpRequest request) {
        requireMethod(request, "POST");
        JsonNode body = body(request);
        return PluginHttpResponse.ok(panorama.trigger(
                RequestPaths.segment(request.path(), 2),
                intOr(body, "width", PanoramaService.DEFAULT_WIDTH),
                intOr(body, "height", PanoramaService.DEFAULT_HEIGHT),
                doubleOr(body, "yaw", PanoramaService.DEFAULT_YAW),
                doubleOr(body, "pitch", PanoramaService.DEFAULT_PITCH),
                doubleOr(body, "fov", PanoramaService.DEFAULT_FOV),
                doubleOr(body, "distance", PanoramaService.DEFAULT_DISTANCE)));
    }

    // ------------------------------------------------------------------
    // 分布式分片 worker
    // ------------------------------------------------------------------

    @PluginHttpEndpoint(method = "GET", path = "/admin/maps/{mapId}/shard-worker",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse shardWorkerStatus(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(shardWorker.status(RequestPaths.segment(request.path(), 2)));
    }

    /**
     * 启动 worker 进程。队列内容由外部生产（见返回体里的 {@code queueHint}）。
     */
    @PluginHttpEndpoint(method = "POST", path = "/admin/maps/{mapId}/shard-worker",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse startShardWorker(PluginHttpRequest request) {
        requireMethod(request, "POST");
        JsonNode body = body(request);
        return PluginHttpResponse.ok(shardWorker.start(
                RequestPaths.segment(request.path(), 2),
                text(body, "workerId"),
                intOr(body, "threads", ShardWorkerService.DEFAULT_THREADS),
                intOr(body, "leaseMinutes", ShardWorkerService.DEFAULT_LEASE_MINUTES),
                intOr(body, "idleMinutes", ShardWorkerService.DEFAULT_IDLE_MINUTES),
                !body.hasNonNull("meshopt") || body.get("meshopt").asBoolean()));
    }

    /** 停止 worker 并把增量产物回传到对象存储。 */
    @PluginHttpEndpoint(method = "DELETE", path = "/admin/maps/{mapId}/shard-worker",
            permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse stopShardWorker(PluginHttpRequest request) {
        requireMethod(request, "DELETE");
        return PluginHttpResponse.ok(shardWorker.stop(RequestPaths.segment(request.path(), 2)));
    }

    // ------------------------------------------------------------------
    // 自检
    // ------------------------------------------------------------------

    @PluginHttpEndpoint(method = "GET", path = "/admin/engine", permission = VoxelithMapPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse engine(PluginHttpRequest request) {
        requireMethod(request, "GET");
        return PluginHttpResponse.ok(maps.engineStatus());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private JsonNode body(PluginHttpRequest request) {
        String raw = request.body();
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (IOException e) {
            throw new IllegalArgumentException("请求体不是合法 JSON");
        }
    }

    /**
     * 解析渲染参数；缺省字段沿用默认值（不覆盖成 0——那会把 minY 变成 0、把批次关掉）。
     */
    private RenderSettings settings(JsonNode node, RenderSettings fallback) {
        RenderSettings base = fallback == null ? RenderSettings.defaults() : fallback;
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return fallback;
        }
        return new RenderSettings(
                null,
                node.hasNonNull("minY") ? node.get("minY").asInt() : base.minY(),
                node.hasNonNull("maxLevel") ? node.get("maxLevel").asInt() : base.maxLevel(),
                base.sampleChunks(),
                node.hasNonNull("lodAtlas") ? node.get("lodAtlas").asBoolean() : base.lodAtlas(),
                node.hasNonNull("meshopt") ? node.get("meshopt").asBoolean() : base.meshopt(),
                node.hasNonNull("maxChunks") ? node.get("maxChunks").asInt() : base.maxChunks(),
                node.hasNonNull("batchChunks") ? node.get("batchChunks").asInt() : base.batchChunks(),
                node.hasNonNull("skipHarvest") ? node.get("skipHarvest").asBoolean() : base.skipHarvest(),
                text(node, "mcVersion") == null ? base.mcVersion() : text(node, "mcVersion"),
                text(node, "loaderVersion") == null ? base.loaderVersion() : text(node, "loaderVersion"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String raw = value.asText("");
        return raw.isBlank() ? null : raw;
    }

    /**
     * 解析模组 jar 列表（{@code [{fileId, name}, ...]}）；节点缺失/非数组时返回 {@code null}
     * ——对 CreateMapCmd 等价于「无模组」，对 UpdateMapCmd 的 {@code null} 语义是「不改动」。
     */
    private static List<online.yudream.plugin.voxelithmap.domain.value.ModJarRef> modJars(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return null;
        }
        List<online.yudream.plugin.voxelithmap.domain.value.ModJarRef> result = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("modJars 数组的元素必须是对象（{fileId, name}）");
            }
            String fileId = text(item, "fileId");
            if (fileId == null) {
                throw new IllegalArgumentException("模组 jar 缺少 fileId");
            }
            result.add(new online.yudream.plugin.voxelithmap.domain.value.ModJarRef(
                    fileId, text(item, "name")));
        }
        return result;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private int intOr(JsonNode node, String field, int fallback) {
        Integer value = integer(node, field);
        return value == null ? fallback : value;
    }

    private double doubleOr(JsonNode node, String field, double fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asDouble(fallback);
    }

    private Optional<Integer> queryInt(PluginHttpRequest request, String name) {
        List<String> values = request.query() == null ? null : request.query().get(name);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(values.get(0).trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("查询参数 " + name + " 需要整数");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void requireMethod(PluginHttpRequest request, String method) {
        if (request != null && request.method() != null && !method.equalsIgnoreCase(request.method())) {
            throw new IllegalArgumentException("仅支持 " + method + " 请求");
        }
    }
}
