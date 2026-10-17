package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.plugin.voxelithmap.application.dto.PanoramaStatusDTO;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.repo.MapProjectRepo;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithCliArguments;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithProcessRunner;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRenderEngine;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 全景图烘焙服务。
 *
 * <p>Voxelith 的 {@code PanoramaCli} 是纯 CPU 的高度场光线步进：读渲染留下的
 * {@code surface.png} + {@code heightfield.bin}，输出透视全景图、正交航拍图与相机参数 JSON。
 * 所以它<b>不重跑 3D 管线</b>，也必须在渲染之后才能跑。</p>
 *
 * <p>产物写进<b>当前已发布代</b>（{@code saveArtifact}），这样：
 * 公开端点 {@code /maps/{id}/panorama.png} 与瓦片走同一套代指针，重新渲染换代会自然让旧全景失效，
 * 不会出现「地图变了全景还是上一版的」。</p>
 */
public final class PanoramaService implements AutoCloseable {

    /** 烘焙超时：830 万像素纯 CPU 步进约 1~3 分钟，留足量。 */
    private static final Duration TIMEOUT = Duration.ofMinutes(20);
    /** 全景产物名单（与 PanoramaCli 的输出约定一致）。 */
    private static final List<String> ARTIFACTS = List.of("panorama.png", "aerial.png", "panorama.json");
    /** 日志尾巴上限。 */
    private static final int LOG_TAIL_CHARS = 2_000;

    public static final int DEFAULT_WIDTH = 3840;
    public static final int DEFAULT_HEIGHT = 2160;
    public static final double DEFAULT_YAW = 0;
    public static final double DEFAULT_PITCH = 45;
    public static final double DEFAULT_FOV = 60;
    public static final double DEFAULT_DISTANCE = 1.25;

    private final MapProjectRepo projectRepo;
    private final MapArtifactStorage storage;
    private final VoxelithRenderEngine engine;
    private final VoxelithRuntimeConfig config;

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "voxelith-panorama");
        thread.setDaemon(true);
        return thread;
    });

    public PanoramaService(MapProjectRepo projectRepo,
                           MapArtifactStorage storage,
                           VoxelithRenderEngine engine,
                           VoxelithRuntimeConfig config) {
        this.projectRepo = projectRepo;
        this.storage = storage;
        this.engine = engine;
        this.config = config;
    }

    /** 读状态；从未触发过时按「当前代有没有全景图」合成一个空闲态。 */
    public PanoramaStatusDTO status(String mapId) {
        MapProject project = requireProject(mapId);
        boolean generated = hasPanorama(project);
        State state = states.get(mapId);
        return state == null ? idle(mapId, generated) : state.toDTO(mapId, generated);
    }

    /**
     * 触发一次烘焙（异步）。
     *
     * @param mapId    地图 id
     * @param width    输出宽（{@code <=0} 用默认）
     * @param height   输出高（{@code <=0} 用默认）
     * @param yaw      水平朝向（度）
     * @param pitch    俯角（度）
     * @param fov      视场角（度）
     * @param distance 相机距离系数
     * @return 触发后的即时状态（此时通常已是 {@code RUNNING}）
     */
    public PanoramaStatusDTO trigger(String mapId, int width, int height,
                                     double yaw, double pitch, double fov, double distance) {
        MapProject project = requireProject(mapId);
        if (project.activeGenerationId() == null) {
            throw new IllegalArgumentException("该地图还没有已发布的渲染产物，请先渲染一次再烘焙全景图");
        }
        if (!running.add(mapId)) {
            throw new IllegalArgumentException("该地图的全景图正在烘焙中，请等待完成");
        }
        int safeWidth = width <= 0 ? DEFAULT_WIDTH : Math.min(width, 8192);
        int safeHeight = height <= 0 ? DEFAULT_HEIGHT : Math.min(height, 8192);
        double safeFov = fov <= 0 ? DEFAULT_FOV : Math.min(fov, 120);
        double safeDistance = distance <= 0 ? DEFAULT_DISTANCE : Math.min(distance, 6);

        State state = new State(safeWidth, safeHeight,
                Double.isNaN(yaw) ? DEFAULT_YAW : yaw,
                Double.isNaN(pitch) ? DEFAULT_PITCH : pitch,
                safeFov, safeDistance);
        state.start();
        states.put(mapId, state);
        worker.submit(() -> run(project, state));
        return state.toDTO(mapId, hasPanorama(project));
    }

    private void run(MapProject project, State state) {
        String mapId = project.id();
        Path workDir = config.mapWorkDir(mapId);
        try {
            state.advance(10, "正在烘焙全景图…");
            VoxelithCliArguments.PanoramaInvocation invocation = new VoxelithCliArguments.PanoramaInvocation(
                    workDir, config.panoramaDir(), mapId,
                    state.width, state.height, state.yaw, state.pitch, state.fov, state.distance,
                    "panorama.png");
            VoxelithProcessRunner.RunResult result = engine.renderPanorama(
                    invocation, workDir, TIMEOUT, line -> state.appendLog(line));

            if (!result.succeeded()) {
                boolean missingSurface = result.exitCode() == 1
                        && result.log() != null && result.log().contains("surface.png");
                state.fail(missingSurface
                        ? "找不到渲染中间产物（surface.png/heightfield.bin），请先重新渲染一次该地图"
                        : result.failureReason());
                return;
            }
            state.advance(90, "正在导入全景产物…");
            int imported = importArtifacts(project, mapId);
            if (imported < ARTIFACTS.size()) {
                state.fail("全景产物不完整（期望 " + ARTIFACTS.size() + " 个，实到 " + imported + " 个）");
                return;
            }
            state.succeed("全景图已生成（" + state.width + "×" + state.height + "）");
        } catch (RuntimeException | IOException e) {
            state.fail("全景烘焙异常: " + e.getMessage());
        } finally {
            running.remove(mapId);
        }
    }

    /** 把全景产物写进当前已发布代。 */
    private int importArtifacts(MapProject project, String mapId) throws IOException {
        String generationId = project.activeGenerationId();
        Path dir = config.mapPanoramaDir(mapId);
        int imported = 0;
        for (String name : ARTIFACTS) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            storage.saveArtifact(mapId, generationId, name, Files.readAllBytes(file));
            imported++;
        }
        return imported;
    }

    private boolean hasPanorama(MapProject project) {
        String generationId = project.activeGenerationId();
        if (generationId == null) {
            return false;
        }
        return storage.artifact(project.id(), generationId, "panorama.png").isPresent();
    }

    private MapProject requireProject(String mapId) {
        Optional<MapProject> found = projectRepo.findById(mapId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("地图不存在: " + mapId);
        }
        return found.get();
    }

    private static PanoramaStatusDTO idle(String mapId, boolean generated) {
        return PanoramaStatusDTO.idle(mapId, generated,
                DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_YAW, DEFAULT_PITCH, DEFAULT_FOV, DEFAULT_DISTANCE);
    }

    @Override
    public void close() {
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 单张地图的烘焙状态（可变，用 volatile 字段保证读取线程可见）。 */
    private static final class State {

        private final int width;
        private final int height;
        private final double yaw;
        private final double pitch;
        private final double fov;
        private final double distance;

        private volatile String state = "RUNNING";
        private volatile int percent;
        private volatile String message = "";
        private volatile String error;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile String logTail = "";

        private State(int width, int height, double yaw, double pitch, double fov, double distance) {
            this.width = width;
            this.height = height;
            this.yaw = yaw;
            this.pitch = pitch;
            this.fov = fov;
            this.distance = distance;
        }

        private void start() {
            this.startedAt = Instant.now();
            this.percent = 0;
            this.message = "已排队";
        }

        private void advance(int percent, String message) {
            this.percent = percent;
            this.message = message;
        }

        private void succeed(String message) {
            this.state = "SUCCEEDED";
            this.percent = 100;
            this.message = message;
            this.finishedAt = Instant.now();
        }

        private void fail(String reason) {
            this.state = "FAILED";
            this.message = "烘焙失败";
            this.error = reason;
            this.finishedAt = Instant.now();
        }

        private synchronized void appendLog(String line) {
            if (line == null) {
                return;
            }
            String merged = logTail + line + "\n";
            logTail = merged.length() > LOG_TAIL_CHARS ? merged.substring(merged.length() - LOG_TAIL_CHARS) : merged;
        }

        private PanoramaStatusDTO toDTO(String mapId, boolean generated) {
            return new PanoramaStatusDTO(mapId, state, percent, message, generated,
                    width, height, yaw, pitch, fov, distance,
                    startedAt == null ? null : startedAt.toString(),
                    finishedAt == null ? null : finishedAt.toString(),
                    error, logTail);
        }
    }
}
