package online.yudream.plugin.voxelithmap.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.aggregate.RenderTask;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.voxelithmap.domain.enumerate.ProjectState;
import online.yudream.plugin.voxelithmap.domain.enumerate.TaskKind;
import online.yudream.plugin.voxelithmap.domain.enumerate.TaskState;
import online.yudream.plugin.voxelithmap.domain.repo.MapProjectRepo;
import online.yudream.plugin.voxelithmap.domain.repo.RenderTaskRepo;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithCliArguments;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithProcessRunner;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRenderEngine;
import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;
import online.yudream.plugin.voxelithmap.infrastructure.engine.PlatformFileMaterializer;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;
import online.yudream.plugin.voxelithmap.support.InMemoryFileStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 编排器生命周期回归测试（取消 + 重启自愈）。
 *
 * <p>修复前的行为（2026-09-26 线上反馈「停止渲染按钮无效」）：点「停止」后
 * {@code Running.cancel()} 调 {@code future.cancel(true)} 中断 worker 线程，
 * {@code VoxelithProcessRunner} 的 {@code waitFor} 抛 {@code InterruptedException}，
 * 编排层把它当渲染异常处理——任务被标成 <b>FAILED</b>（错误文案「渲染线程被中断」），
 * 而不是 CANCELED。排队中的任务被取消时同样标 FAILED。</p>
 *
 * <p>修复后取消只置标志位：运行中的任务由引擎取消探针优雅终止子进程，走
 * {@code RunResult.canceled()} → {@code cancelTask()} 的正路；排队中的任务由
 * {@code execute()} 开头的检查兜住。两个用例分别锁住这两条路径。</p>
 */
class RenderOrchestratorLifecycleTest {

    @TempDir
    Path tempDir;

    private final InMemoryTaskRepo taskRepo = new InMemoryTaskRepo();
    private final InMemoryProjectRepo projectRepo = new InMemoryProjectRepo();
    private RenderOrchestrator orchestrator;

    @AfterEach
    void tearDown() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 运行中的任务被取消：终态必须是 CANCELED 而不是 FAILED。 */
    @Test
    void 运行中取消落CANCELED而不是FAILED() throws Exception {
        ControllableEngine engine = new ControllableEngine();
        orchestrator = orchestrator(engine, 1);
        MapProject project = project("m-run");
        projectRepo.save(project);

        RenderTask task = orchestrator.submit(project);
        assertTrue(engine.entered.await(10, TimeUnit.SECONDS), "渲染应已进入引擎");

        assertTrue(orchestrator.cancel(task.id()), "运行中的任务应可取消");
        awaitTerminalState(task.id(), TaskState.CANCELED);

        RenderTask finished = taskRepo.findById(task.id()).orElseThrow();
        assertEquals(TaskState.CANCELED, finished.state(),
                "点「停止」后任务必须是「已取消」：" + finished.state() + " / " + finished.error());
        assertEquals("已取消", finished.message());
    }

    /** 排队中的任务被取消：不执行、不拉子进程，终态 CANCELED 且从未 start。 */
    @Test
    void 排队中取消不执行且落CANCELED() throws Exception {
        ControllableEngine engine = new ControllableEngine();
        orchestrator = orchestrator(engine, 1);
        MapProject first = project("m-first");
        projectRepo.save(first);
        MapProject queued = project("m-queued");
        projectRepo.save(queued);

        RenderTask firstTask = orchestrator.submit(first);
        // 并发 1：第一个任务占住 worker，确认它已进引擎
        assertTrue(engine.entered.await(10, TimeUnit.SECONDS), "第一个任务应已进入引擎");

        RenderTask queuedTask = orchestrator.submit(queued);
        assertEquals(TaskState.PENDING, queuedTask.state(), "并发占满时新任务应排队");

        assertTrue(orchestrator.cancel(queuedTask.id()), "排队中的任务应可取消");
        // 放行第一个任务，让排队的任务获得执行机会
        engine.release.countDown();
        awaitTerminalState(firstTask.id(), TaskState.CANCELED);

        awaitTerminalState(queuedTask.id(), TaskState.CANCELED);
        RenderTask finished = taskRepo.findById(queuedTask.id()).orElseThrow();
        assertEquals(TaskState.CANCELED, finished.state(), "排队中被取消必须落 CANCELED");
        assertNull(finished.startedAt(), "被取消的排队任务不应真正开始执行");
        assertNull(finished.error(), "取消不是失败，不应有错误信息");
    }

    /** 不存在或已结束的任务取消：返回 false（前端据此提示「任务已结束」）。 */
    @Test
    void 不存在的任务取消返回false() {
        ControllableEngine engine = new ControllableEngine();
        orchestrator = orchestrator(engine, 1);
        assertFalse(orchestrator.cancel("task-not-exist"));
    }

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    /**
     * 可控引擎：进入 {@code render} 后在「放行闩 / 取消探针」上等待，被取消时返回
     * canceled 结果——与真实 {@code VoxelithProcessRunner} 的探针语义一致。
     */
    /**
     * 重启自愈必须能处理**非渲染任务**的残留。
     *
     * <p>修复前：{@code recoverStaleTasks} 无条件取 {@code task.generation()}，而导入/删除这类任务
     * 没有渲染代，方法直接抛 {@link IllegalStateException}——插件启用时的整轮自愈因此中断，
     * 后面的渲染残留任务反而漏收（启用处把异常吞掉，现场只剩一句「自愈失败」）。</p>
     */
    @Test
    void recoverStaleTasksFailsLeftoversIncludingNonRenderJobs() {
        MapProject project = project("map-stale");
        projectRepo.save(project);
        RenderTask render = RenderTask.create("task-render", new MapGeneration("map-stale", "gen-1"));
        render.start();
        taskRepo.save(render);
        // 浏览器搬运的导入任务：没有渲染代，重启前同样是残留
        RenderTask importTask = RenderTask.createJob("task-import", TaskKind.IMPORT, "生存服");
        importTask.start();
        taskRepo.save(importTask);

        int recovered = orchestrator(new ControllableEngine(), 1).recoverStaleTasks();

        assertEquals(2, recovered);
        assertEquals(TaskState.FAILED, taskRepo.findById("task-render").orElseThrow().state());
        assertEquals(TaskState.FAILED, taskRepo.findById("task-import").orElseThrow().state());
        // 渲染残留连带把地图标失败；导入任务没有地图记录，不应该再抛错
        assertEquals(ProjectState.FAILED, projectRepo.findById("map-stale").orElseThrow().state());
    }

    private static final class ControllableEngine implements VoxelithRenderEngine {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public RenderOutcome render(VoxelithCliArguments.RenderInvocation invocation, Path taskDir,
                                    java.util.function.BooleanSupplier canceled,
                                    ProgressSink progress, VoxelithProcessRunner.LineConsumer onLine) {
            entered.countDown();
            while (!canceled.getAsBoolean()) {
                try {
                    if (release.await(10, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new RenderOutcome(
                            new VoxelithProcessRunner.RunResult(-1, false, false, "interrupted", null), null);
                }
            }
            return new RenderOutcome(
                    new VoxelithProcessRunner.RunResult(1, false, true, "用户取消", null), null);
        }
    }

    /** 文件名不带版本号 → 采集自动判定交给引擎 → 测试里不会触发模型采集。 */
    private static MapProject project(String id) {
        return MapProject.create(id, "地图-" + id, "world", "file-world-" + id, "file-jar-" + id,
                "client.jar", List.of(), 0, 64, 0, RenderSettings.defaults());
    }

    private RenderOrchestrator orchestrator(VoxelithRenderEngine engine, int concurrency) {
        VoxelithRuntimeConfig config = new VoxelithRuntimeConfig(
                Path.of("java"), tempDir.resolve("runtime"), null, List.of("worker.jar"), null,
                4096, 180, concurrency, false, 0, 2048, true);
        InMemoryFileStore fileStore = new InMemoryFileStore();
        MapArtifactStorage storage = new MapArtifactStorage(fileStore);
        GenerationPublisher publisher = new GenerationPublisher(storage);
        PlatformFileMaterializer files = new PlatformFileMaterializer(frameworkServices(), null);
        return new RenderOrchestrator(projectRepo, taskRepo, storage, publisher, engine,
                config, files, new VoxelithEventStream(), new com.fasterxml.jackson.databind.ObjectMapper(), null,
                new online.yudream.plugin.voxelithmap.infrastructure.storage.TaskLogStore(config));
    }

    /**
     * 只实现 {@code platformFile}（物料落盘用），其余方法不应被触达。
     * 接口方法多，用动态代理比手写匿名类省事。
     *
     * <p>world 存档必须是含 {@code level.dat} 的合法 zip——prepare 阶段真的会解包。</p>
     */
    private FrameworkServices frameworkServices() {
        return (FrameworkServices) Proxy.newProxyInstance(
                FrameworkServices.class.getClassLoader(),
                new Class<?>[]{FrameworkServices.class},
                (proxy, method, args) -> {
                    if ("platformFile".equals(method.getName())) {
                        String fileId = (String) args[0];
                        byte[] data = fileId.startsWith("file-world")
                                ? minimalWorldZip()
                                : ("bytes-of-" + fileId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        return Optional.of(new PluginStoredFile(fileId, "application/octet-stream",
                                (long) data.length, new ByteArrayInputStream(data)));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 构造一个最小的合法存档 zip（顶层 level.dat）。 */
    private static byte[] minimalWorldZip() {
        try (var bytes = new java.io.ByteArrayOutputStream();
             var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("level.dat"));
            zip.write("fake-level-dat".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 轮询等待任务到达期望终态（worker 是异步的，不能同步断言）。 */
    private void awaitTerminalState(String taskId, TaskState expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() - deadline < 0) {
            Optional<RenderTask> task = taskRepo.findById(taskId);
            if (task.isPresent() && task.orElseThrow().state().isFinished()) {
                assertEquals(expected, task.orElseThrow().state(),
                        "任务终态不符合预期，error=" + task.orElseThrow().error());
                return;
            }
            Thread.sleep(25);
        }
        fail("任务未在期限内到达终态 " + expected);
    }

    // ------------------------------------------------------------------
    // 内存仓储
    // ------------------------------------------------------------------

    private static final class InMemoryTaskRepo implements RenderTaskRepo {

        private final Map<String, RenderTask> tasks = new ConcurrentHashMap<>();

        @Override
        public RenderTask save(RenderTask task) {
            tasks.put(task.id(), task);
            return task;
        }

        @Override
        public Optional<RenderTask> findById(String taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public List<RenderTask> findByMapId(String mapId) {
            return tasks.values().stream().filter(task -> task.mapId().equals(mapId))
                    .sorted(Comparator.comparing(RenderTask::createdAt).reversed()).toList();
        }

        @Override
        public List<RenderTask> findAll() {
            return tasks.values().stream()
                    .sorted(Comparator.comparing(RenderTask::createdAt).reversed()).toList();
        }

        @Override
        public void deleteByMapId(String mapId) {
            tasks.values().removeIf(task -> task.mapId().equals(mapId));
        }
    }

    private static final class InMemoryProjectRepo implements MapProjectRepo {

        private final Map<String, MapProject> projects = new ConcurrentHashMap<>();

        @Override
        public MapProject save(MapProject project) {
            projects.put(project.id(), project);
            return project;
        }

        @Override
        public Optional<MapProject> findById(String mapId) {
            return Optional.ofNullable(projects.get(mapId));
        }

        @Override
        public List<MapProject> findAll() {
            return List.copyOf(projects.values());
        }

        @Override
        public void delete(String mapId) {
            projects.remove(mapId);
        }

        @Override
        public long count() {
            return projects.size();
        }
    }
}
