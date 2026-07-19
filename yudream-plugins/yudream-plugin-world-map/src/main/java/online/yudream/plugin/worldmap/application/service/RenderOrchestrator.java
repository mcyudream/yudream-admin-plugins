package online.yudream.plugin.worldmap.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.plugin.worldmap.application.assembler.WorldMapAppAssembler;
import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;
import online.yudream.plugin.worldmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.worldmap.domain.aggregate.RenderTask;
import online.yudream.plugin.worldmap.domain.repo.MapInstanceRepo;
import online.yudream.plugin.worldmap.domain.repo.RenderTaskRepo;
import online.yudream.plugin.worldmap.domain.enumerate.TaskState;
import online.yudream.plugin.worldmap.domain.enumerate.RenderPhase;
import online.yudream.plugin.worldmap.infrastructure.render.DefaultWorldMapRenderer;
import online.yudream.plugin.worldmap.infrastructure.render.ProgressListener;
import online.yudream.plugin.worldmap.infrastructure.render.RenderJob;
import online.yudream.plugin.worldmap.infrastructure.render.RenderSummary;
import online.yudream.plugin.worldmap.infrastructure.render.TileSink;
import online.yudream.plugin.worldmap.infrastructure.render.WorldMapRenderer;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;
import online.yudream.plugin.worldmap.infrastructure.world.WorldArchive;
import online.yudream.plugin.worldmap.infrastructure.world.WorldTileManifest;
import online.yudream.plugin.worldmap.infrastructure.render.bluemap.BlueMapRenderConfiguration;
import online.yudream.plugin.worldmap.infrastructure.render.bluemap.BlueMapRenderConfigurationResolver;
import online.yudream.plugin.worldmap.infrastructure.render.bluemap.BlueMapRenderEngineAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.stream.Stream;

/**
 * 渲染编排器：单线程队列执行渲染任务，负责存档解包、资产解析、进度上报与资源回收。
 */
public class RenderOrchestrator implements AutoCloseable {

    private static final String DEFAULT_CLIENT_JAR_URL = "https://bmclapi2.bangbang93.com/version/1.21.4/client";
    private static final String CLIENT_JAR_URL_SETTING = "yudream.world-map.client-jar-url";
    private static final long PROGRESS_PUBLISH_INTERVAL_MS = 250;

    private final RenderTaskRepo taskRepo;
    private final MapInstanceRepo mapRepo;
    private final TileStorage tileStorage;
    private final FrameworkServices framework;
    private final WorldMapRenderer renderer;
    private final WorldMapEventStream eventStream;
    private final GenerationPublisher generationPublisher;
    private final BlueMapRenderConfigurationResolver blueMapConfigurationResolver;
    private final BlueMapRenderEngineAdapter blueMapRenderer;
    private final WorldMapAppAssembler assembler = new WorldMapAppAssembler();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "world-map-render");
        thread.setDaemon(true);
        return thread;
    });
    /** 任务 ID → 运行句柄（用于取消） */
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public RenderOrchestrator(RenderTaskRepo taskRepo,
                              MapInstanceRepo mapRepo,
                              TileStorage tileStorage,
                               FrameworkServices framework,
                               WorldMapRenderer renderer,
                               WorldMapEventStream eventStream) {
        this(taskRepo, mapRepo, tileStorage, framework, renderer, eventStream, new GenerationPublisher(tileStorage));
    }

    public RenderOrchestrator(RenderTaskRepo taskRepo,
                              MapInstanceRepo mapRepo,
                              TileStorage tileStorage,
                              FrameworkServices framework,
                              WorldMapRenderer renderer,
                              WorldMapEventStream eventStream,
                              GenerationPublisher generationPublisher) {
        this.taskRepo = taskRepo;
        this.mapRepo = mapRepo;
        this.tileStorage = tileStorage;
        this.framework = framework;
        this.renderer = renderer;
        this.eventStream = eventStream;
        this.generationPublisher = generationPublisher;
        this.blueMapConfigurationResolver = new BlueMapRenderConfigurationResolver();
        this.blueMapRenderer = new BlueMapRenderEngineAdapter();
        recoverStaleTasks();
    }

    /** 插件（服务）重启后，此前 PENDING/RUNNING 的任务一律标记为中断失败。 */
    private void recoverStaleTasks() {
        for (RenderTask task : taskRepo.findAll()) {
            if (task.getState() == TaskState.PENDING || task.getState() == TaskState.RUNNING) {
                task.fail("服务重启，任务中断");
                taskRepo.save(task);
                mapRepo.findById(task.getMapId()).ifPresent(map -> {
                    if (map.getState() == online.yudream.plugin.worldmap.domain.enumerate.MapState.RENDERING) {
                        map.markFailed("服务重启，任务中断");
                        mapRepo.save(map);
                    }
                });
                publish(task);
            }
        }
    }

    /** 取消任务：中断渲染线程，渲染器在下一个 tile 边界退出。 */
    public synchronized boolean cancel(String taskId) {
        Future<?> future = runningTasks.remove(taskId);
        if (future == null) {
            return false;
        }
        if (taskRepo.findById(taskId).map(RenderTask::isTerminal).orElse(true)) {
            return false;
        }
        boolean cancelled = future.cancel(true);
        if (!cancelled) {
            return false;
        }
        markCancelled(taskId, "渲染任务已取消");
        return true;
    }

    public RenderTask submit(MapInstance map) {
        RenderTask task = new RenderTask(UUID.randomUUID().toString().replace("-", "").substring(0, 12), map.getId());
        taskRepo.save(task);
        map.markRendering();
        mapRepo.save(map);
        publish(task);
        FutureTask<Void> future = new FutureTask<>(() -> {
            runTask(task, map);
            return null;
        });
        runningTasks.put(task.getId(), future);
        executor.execute(future);
        return task;
    }

    private void runTask(RenderTask task, MapInstance map) {
        Path workDir = null;
        MapGeneration generation = null;
        try {
            updatePhase(task, RenderPhase.IMPORT, 0, "Preparing render input");
            workDir = Files.createTempDirectory("world-map-render-");
            updatePhase(task, RenderPhase.IMPORT, 100, "Render input prepared");
            updatePhase(task, RenderPhase.EXTRACT, 0, "Extracting world archive");
            Path worldZip = materialize(
                    tileStorage.worldZip(map.getId()).orElseThrow(() -> new IllegalArgumentException("世界存档缺失")),
                    workDir.resolve("world.zip"), "world.zip");
            Path extracted = WorldArchive.extract(worldZip, workDir.resolve("world"));
            Path worldRoot = WorldArchive.resolveWorldRoot(extracted);
            updatePhase(task, RenderPhase.EXTRACT, 100, "World archive extracted");
            int[] spawn = WorldArchive.spawn(worldRoot);
            map.setSpawnX(spawn[0]);
            map.setSpawnY(spawn[1]);
            map.setSpawnZ(spawn[2]);
            WorldTileManifest manifest = WorldArchive.tileManifest(worldRoot, map.getDimension());
            int[] range = new int[]{manifest.minTileX(), manifest.minTileZ(), manifest.maxTileX(), manifest.maxTileZ()};
            map.setMinTileX(range[0]);
            map.setMinTileZ(range[1]);
            map.setMaxTileX(range[2]);
            map.setMaxTileZ(range[3]);
            updatePhase(task, RenderPhase.ASSETS, 0, "Preparing client assets");
            Path clientJar = resolveClientJar(map, workDir.resolve("client.jar"));
            mapRepo.save(map);

            int totalTiles = Math.toIntExact(manifest.tileCount());
            task.start(totalTiles);
            updatePhase(task, RenderPhase.ASSETS, 100, "Client assets prepared");

            generation = generationPublisher.stage(map.getId());

            RenderJob job = new RenderJob(
                    worldRoot,
                    clientJar,
                    map.getDimension(),
                    range[0], range[1], range[2], range[3],
                    map.isStripNetherCeiling(), manifest
            );
            ThrottledProgress throttledProgress = new ThrottledProgress(task);
            java.util.Optional<BlueMapRenderConfiguration> blueMap = blueMapConfigurationResolver.resolve(framework);
            RenderSummary summary = render(job, map, workDir, generation, blueMap, throttledProgress);
            throttledProgress.flush();

            if (isCancelled(task.getId()) || Thread.currentThread().isInterrupted()) {
                discard(generation);
                markCancelled(task.getId(), "渲染任务已取消");
                return;
            }
            synchronized (this) {
                if (isCancelled(task.getId()) || Thread.currentThread().isInterrupted()) {
                    discard(generation);
                    markCancelled(task.getId(), "渲染任务已取消");
                    return;
                }
                task.advance(summary.hiresTiles(), summary.hiresTiles(), "渲染完成");
                updatePhase(task, RenderPhase.PUBLISH, 0, "Publishing render output");
                generationPublisher.publish(map, generation, blueMap.isPresent() ? "BLUEMAP" : "YUDREAM");
                map.markReady(summary.hiresTiles(), summary.lowresTiles());
                mapRepo.save(map);
                updatePhase(task, RenderPhase.PUBLISH, 100, "Render output published");
                task.succeed();
                taskRepo.save(task);
                publish(task);
            }
        } catch (DefaultWorldMapRenderer.InterruptedRenderException e) {
            discard(generation);
            markCancelled(task.getId(), "渲染任务已取消");
        } catch (Exception e) {
            if (isCancelled(task.getId()) || Thread.currentThread().isInterrupted()) {
                discard(generation);
                markCancelled(task.getId(), "渲染任务已取消");
                return;
            }
            discard(generation);
            task.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            taskRepo.save(task);
            map.markFailed(task.getError());
            mapRepo.save(map);
            publish(task);
        } finally {
            runningTasks.remove(task.getId());
            deleteRecursively(workDir);
        }
    }

    private synchronized void markCancelled(String taskId, String message) {
        taskRepo.findById(taskId).ifPresent(task -> {
            task.cancel(message);
            taskRepo.save(task);
            mapRepo.findById(task.getMapId()).ifPresent(map -> {
                if (map.getState() == online.yudream.plugin.worldmap.domain.enumerate.MapState.RENDERING) {
                    map.markCancelled(message);
                    mapRepo.save(map);
                }
            });
            publish(task);
        });
    }

    private boolean isCancelled(String taskId) {
        return taskRepo.findById(taskId)
                .map(task -> task.getState() == TaskState.CANCELLED)
                .orElse(false);
    }

    private Path materialize(PluginStoredFile file, Path target, String label) throws IOException {
        if (file == null || file.inputStream() == null) {
            throw new IllegalArgumentException("存储对象不存在：" + label);
        }
        try (InputStream input = file.inputStream()) {
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private Path resolveClientJar(MapInstance map, Path target) throws IOException, InterruptedException {
        if (map.getClientJarKey() != null && !map.getClientJarKey().isBlank()) {
            return materialize(
                    tileStorage.clientJar(map.getId()).orElseThrow(() -> new IllegalArgumentException("客户端 jar 缺失")),
                    target, "client.jar");
        }
        String url = framework.setting(CLIENT_JAR_URL_SETTING).orElse(DEFAULT_CLIENT_JAR_URL);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpResponse<byte[]> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
        if (response.statusCode() != 200 || response.body().length == 0) {
            throw new IllegalStateException("客户端 jar 下载失败：HTTP " + response.statusCode());
        }
        Files.write(target, response.body());
        tileStorage.saveClientJar(map.getId(), response.body());
        map.setClientJarKey("maps/" + map.getId() + "/client.jar");
        return target;
    }

    private void publish(RenderTask task) {
        eventStream.publish(assembler.toDTO(task));
    }

    private void updatePhase(RenderTask task, RenderPhase phase, int percent, String message) {
        task.advancePhase(phase, percent, message);
        taskRepo.save(task);
        publish(task);
    }

    private RenderSummary render(RenderJob job, MapInstance map, Path workDir, MapGeneration generation,
                                 java.util.Optional<BlueMapRenderConfiguration> blueMap, ThrottledProgress progress) throws IOException {
        if (blueMap.isPresent()) {
            return blueMapRenderer.render(job, map.getId(), workDir, generation, generationPublisher, blueMap.get(), progress);
        }
        return renderer.render(job, new StorageTileSink(generation), progress);
    }

    private void discard(MapGeneration generation) {
        if (generation != null) {
            generationPublisher.discard(generation);
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 临时目录清理失败不影响渲染结果
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 渲染产物写入插件文件存储。
     */
    private class StorageTileSink implements TileSink {
        private final MapGeneration generation;

        StorageTileSink(MapGeneration generation) {
            this.generation = generation;
        }

        @Override
        public void putHiresTile(int tx, int tz, byte[] gzipJson) throws IOException {
            generationPublisher.saveHires(generation, tx, tz, gzipJson);
        }

        @Override
        public void putLowresTile(int lod, int tx, int tz, byte[] png) throws IOException {
            generationPublisher.saveLowres(generation, lod, tx, tz, png);
        }

        @Override
        public void putAtlas(byte[] png) throws IOException {
            generationPublisher.saveAtlas(generation, png);
        }
    }

    /**
     * 节流进度回调：落库 + SSE 推送。
     */
    private class ThrottledProgress implements ProgressListener {
        private final RenderTask task;
        private long lastPublish;
        private int done;
        private int total;
        private String message;
        private RenderPhase phase = RenderPhase.HIRES;

        ThrottledProgress(RenderTask task) {
            this.task = task;
        }

        @Override
        public synchronized void progress(int done, int total, String message) {
            this.done = done;
            this.total = total;
            this.message = message;
            long now = System.currentTimeMillis();
            if (now - lastPublish >= PROGRESS_PUBLISH_INTERVAL_MS) {
                lastPublish = now;
                flush();
            }
        }

        @Override
        public synchronized void phase(RenderPhase phase, String message) {
            this.phase = phase;
            this.message = message;
            task.advancePhase(phase, 0, message);
            taskRepo.save(task);
            publish(task);
        }

        synchronized void flush() {
            int percent = total <= 0 ? 0 : Math.round(done * 100f / total);
            task.advancePhase(phase, percent, message);
            task.advance(done, total, message);
            taskRepo.save(task);
            publish(task);
        }
    }
}
