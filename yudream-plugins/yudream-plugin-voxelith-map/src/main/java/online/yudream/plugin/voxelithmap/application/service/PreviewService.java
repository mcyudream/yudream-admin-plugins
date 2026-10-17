package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.application.dto.PreviewDTO;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.infrastructure.engine.PlatformFileMaterializer;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;
import online.yudream.plugin.voxelithmap.infrastructure.sidecar.VoxelithSidecar;
import online.yudream.plugin.voxelithmap.infrastructure.sidecar.VoxelithSidecarClient;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;
import online.yudream.plugin.voxelithmap.infrastructure.world.WorldArchiveNormalizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 框选底图编排：把地图项目的存档送进边车，产出带群系染色的二维预览。
 *
 * <p>这是选用「边车」而不是自建底图的唯一理由——Voxelith 的 {@code WorldPreviewRenderer}
 * 会用资源包贴图平均色 + 群系染色渲染地表色图，并顺带给出地表高度范围
 * （{@code minSurfaceY}），用于给「最低渲染高度」一个可信默认值。这些能力只在 Spring
 * 上下文里，没有 CLI 入口，因此必须让边车活着。</p>
 *
 * <p>存档包的形态不能想当然：边车解包用 JDK 的 {@link java.util.zip.ZipInputStream}，
 * 它拒收「STORED + 数据描述符」的条目（一批第三方压缩工具恰恰这么写）。所以交付前会先探测
 * 并按需规整（{@link WorldArchiveNormalizer}），别再把「包是合法的 zip」当成「边车吃得下」。</p>
 *
 * <p>边车不可用时**不报错**，而是返回一个 {@code ready=false, error=...} 的 DTO：
 * 预览是可选能力，它挂了不该让「上传存档 → 填范围 → 渲染」这条路走不通
 * （管理端仍可手填方块范围，参数与框选结果完全等价）。</p>
 */
public final class PreviewService {

    /** 前端框选用的窗口图推荐边长（像素）。 */
    private static final int DEFAULT_WINDOW_SIZE = 512;

    private final MapArtifactStorage storage;
    private final VoxelithSidecar sidecar;
    private final VoxelithSidecarClient client;
    private final VoxelithRuntimeConfig config;
    private final PlatformFileMaterializer files;

    public PreviewService(MapArtifactStorage storage,
                          VoxelithSidecar sidecar,
                          VoxelithSidecarClient client,
                          VoxelithRuntimeConfig config,
                          PlatformFileMaterializer files) {
        this.storage = storage;
        this.sidecar = sidecar;
        this.client = client;
        this.config = config;
        this.files = files;
    }

    /** 查询当前预览状态（不触发新任务）。 */
    public PreviewDTO status(MapProject project) {
        if (!config.sidecarEnabled()) {
            return unavailable(project, "边车已在配置中关闭（yudream.voxelith-map.sidecar-enabled=false）", null);
        }
        if (!sidecar.ensureRunning()) {
            return unavailable(project, sidecar.lastError() == null
                    ? "边车未就绪（状态 " + sidecar.state() + "）" : sidecar.lastError(), null);
        }
        try {
            return withUpload(project, uploadId -> {
                VoxelithSidecarClient.PreviewStatus status =
                        client.previewStatus(uploadId, project.settings().dimension());
                return toDto(project, uploadId, status, null);
            });
        } catch (IOException e) {
            return unavailable(project, e.getMessage(), null);
        }
    }

    /** 触发预览生成（幂等）。 */
    public PreviewDTO trigger(MapProject project) {
        if (!config.sidecarEnabled()) {
            return unavailable(project, "边车已在配置中关闭（yudream.voxelith-map.sidecar-enabled=false）", null);
        }
        if (!sidecar.ensureRunning()) {
            return unavailable(project, sidecar.lastError() == null
                    ? "边车未就绪（状态 " + sidecar.state() + "）" : sidecar.lastError(), null);
        }
        try {
            return withUpload(project, uploadId -> {
                VoxelithSidecarClient.PreviewStatus status =
                        client.requestPreview(uploadId, project.settings().dimension());
                return toDto(project, uploadId, status, null);
            });
        } catch (IOException e) {
            return unavailable(project, e.getMessage(), null);
        }
    }

    /** 底图 PNG（未就绪返回空）。 */
    public Optional<byte[]> previewPng(MapProject project, Integer step) throws IOException {
        return withUpload(project, uploadId -> client.previewPng(uploadId, project.settings().dimension(), step));
    }

    /** 框选窗口图（放大镜用）。 */
    public Optional<byte[]> windowPng(MapProject project, int minX, int minZ, int size, int step)
            throws IOException {
        return withUpload(project, uploadId ->
                client.windowPng(uploadId, project.settings().dimension(), minX, minZ, size, step));
    }

    @FunctionalInterface
    private interface UploadCall<T> {
        T apply(String uploadId) throws IOException;
    }

    /**
     * 用有效的边车上传 id 执行预览调用。
     *
     * <p>自愈：缓存的映射 id 在边车侧可能已失效（边车重装/数据目录被清），此时边车返回
     * 「存档不存在」。第一次遇到就清掉映射、重新上传登记一次再重试；第二次仍失败则原样抛出。</p>
     */
    private <T> T withUpload(MapProject project, UploadCall<T> call) throws IOException {
        try {
            return call.apply(ensureUploadId(project));
        } catch (IOException e) {
            if (!isMissingArchive(e)) {
                throw e;
            }
            storage.clearSidecarLink(project.id());
            return call.apply(ensureUploadId(project));
        }
    }

    /** 边车返回「存档不存在」（HTTP 400/404 携带该文案）= 缓存的上传 id 已失效。 */
    private static boolean isMissingArchive(IOException e) {
        String message = String.valueOf(e.getMessage());
        return message.contains("存档不存在")
                || message.contains("sidecar returned HTTP 400")
                || message.contains("sidecar returned HTTP 404");
    }

    /**
     * 确保边车侧已经登记过这份存档。
     *
     * <p>缓存的映射里若记录了不同的平台文件 id，说明存档被换过，必须重新登记
     * ——否则预览会一直显示上一份存档。</p>
     */
    private String ensureUploadId(MapProject project) throws IOException {
        String sourceFileId = project.worldFileId();
        if (sourceFileId == null || sourceFileId.isBlank()) {
            throw new IOException("该地图还没绑定存档");
        }
        Optional<String[]> cached = storage.sidecarLink(project.id());
        if (cached.isPresent() && sourceFileId.equals(cached.get()[0])) {
            return cached.get()[1];
        }

        Path staging = config.runtimeRoot().resolve("preview").resolve(project.id());
        Files.createDirectories(staging);
        Path archive = files.materialize(sourceFileId, staging.resolve("world.zip"), "存档文件");
        // 边车是未改动的上游，它用 JDK 的 ZipInputStream 解包，会拒收「STORED + 数据描述符」
        // 的条目（详见 WorldArchiveNormalizer）。预览这条路必须把包交给它，所以先规整。
        //
        // 2.1.23 曾在这里「收窄到本维度目录」，实测预览反而更差（删掉其它维度后边车定位不到它要的东西），
        // 已撤回：预览与渲染看到同一份存档，是本插件与边车之间唯一靠得住的约定。
        Path payload = archive;
        if (WorldArchiveNormalizer.needsRepair(archive)) {
            payload = WorldArchiveNormalizer.repairInto(archive, staging.resolve("world-normalized.zip"));
        }
        String name = project.worldName() == null || project.worldName().isBlank()
                ? project.name() : project.worldName();
        // 文件名仍报原名：边车拿它推导存档 id，别把本地临时名带出去
        VoxelithSidecarClient.Upload upload =
                client.uploadArchive(payload, name, archive.getFileName().toString());
        if (upload.id().isBlank()) {
            throw new IOException("边车未返回存档 id");
        }
        storage.saveSidecarLink(project.id(), sourceFileId, upload.id());
        // 存档已交给边车（它自己解包到自己的 upload 目录），本地临时 zip 不再需要
        Files.deleteIfExists(archive);
        if (!payload.equals(archive)) {
            Files.deleteIfExists(payload);
        }
        return upload.id();
    }

    private PreviewDTO toDto(MapProject project, String uploadId, VoxelithSidecarClient.PreviewStatus status,
                             String error) {
        VoxelithSidecarClient.PreviewMeta meta = status.meta();
        PreviewDTO.Meta dtoMeta = meta == null ? null : new PreviewDTO.Meta(
                meta.version(), meta.originX(), meta.originZ(), meta.step(), meta.width(), meta.depth(),
                meta.blockMinX(), meta.blockMinZ(), meta.blockMaxX(), meta.blockMaxZ(),
                meta.regionCount(), meta.chunkCount(), meta.minSurfaceY(), meta.maxSurfaceY());
        return new PreviewDTO(
                status.ready(),
                status.state(),
                status.percent(),
                status.done(),
                status.total(),
                status.message(),
                uploadId,
                project.settings().dimension(),
                error,
                dtoMeta,
                meta == null ? null : meta.suggestedMinY().orElse(null),
                "maps/" + project.id() + "/preview.png",
                DEFAULT_WINDOW_SIZE);
    }

    private PreviewDTO unavailable(MapProject project, String error, String uploadId) {
        return new PreviewDTO(false, "UNAVAILABLE", -1, 0, 0, error, uploadId,
                project.settings().dimension(), error, null, null,
                "maps/" + project.id() + "/preview.png", DEFAULT_WINDOW_SIZE);
    }
}
