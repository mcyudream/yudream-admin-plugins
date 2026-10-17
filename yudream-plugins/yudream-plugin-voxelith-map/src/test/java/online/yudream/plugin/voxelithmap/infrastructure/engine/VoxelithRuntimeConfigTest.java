package online.yudream.plugin.voxelithmap.infrastructure.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行配置的路径布局测试。
 *
 * <p>重点是「工作目录与发布目录必须持久」：它们一旦退化到任务临时目录，
 * 全景图与分片 worker 就失去了输入（渲染任务结束即删）。</p>
 */
class VoxelithRuntimeConfigTest {

    private static VoxelithRuntimeConfig config() {
        return new VoxelithRuntimeConfig(
                Path.of("C:/java/bin/java.exe"), Path.of("C:/runtime"),
                null, List.of("C:/runtime/worker.jar"),
                null, 4096, 180, 1, false, 0, 2048, true);
    }

    @Test
    void 工作与发布目录都在运行根下且相互独立() {
        VoxelithRuntimeConfig config = config();
        assertEquals(Path.of("C:/runtime/work"), config.workRoot());
        assertEquals(Path.of("C:/runtime/publish"), config.publishRoot());
        assertEquals(Path.of("C:/runtime/panorama"), config.panoramaDir());
        assertEquals(Path.of("C:/runtime/queue"), config.queueDir());
        assertEquals(Path.of("C:/runtime/tasks"), config.tasksDir());
    }

    @Test
    void 地图级目录按mapId分片() {
        VoxelithRuntimeConfig config = config();
        assertEquals(Path.of("C:/runtime/work/map01"), config.mapWorkDir("map01"));
        assertEquals(Path.of("C:/runtime/publish/map01"), config.mapPublishDir("map01"));
        assertEquals(Path.of("C:/runtime/panorama/map01"), config.mapPanoramaDir("map01"));
    }

    @Test
    void 非法地图id被消毒到单层目录() {
        VoxelithRuntimeConfig config = config();
        Path dir = config.mapWorkDir("../../etc/passwd");
        // 分隔符与 .. 都被替换，绝不可能跳出运行根
        assertTrue(dir.startsWith(config.workRoot()), "不能跳出工作根：" + dir);
        assertEquals(1, config.workRoot().relativize(dir).getNameCount(), "只应剩一层目录：" + dir);
    }

    @Test
    void 空地图id被拒绝() {
        VoxelithRuntimeConfig config = config();
        assertThrows(IllegalArgumentException.class, () -> config.mapWorkDir("  "));
        assertThrows(IllegalArgumentException.class, () -> config.mapWorkDir(null));
    }

    @Test
    void 显式models文件与分桶缓存互不干扰() {
        VoxelithRuntimeConfig explicit = new VoxelithRuntimeConfig(
                Path.of("C:/java/bin/java.exe"), Path.of("C:/runtime"),
                null, List.of(), Path.of("D:/cache/models.json.gz"),
                4096, 180, 1, false, 0, 2048, true);
        assertEquals(Path.of("D:/cache/models.json.gz"), explicit.configuredModelsFile());
        assertNull(config().configuredModelsFile(),
                "没配显式文件就该是 null，而不是回退到某个默认单文件：" + config().configuredModelsFile());
        assertEquals(Path.of("C:/runtime/models"), config().modelsDir(), "分桶根固定在运行根下");
    }

    /**
     * 采集产物按「版本 + 资源包」分桶。
     *
     * <p>单一共享的 models.json.gz 在「按资源包/存档自动判定采集版本」之后必然串味：
     * 1.21 的地图会复用 1.20.1 的采集产物，而模型里引用的贴图名与包对不上——整片品红。</p>
     */
    @Test
    void 采集缓存按版本与资源包分桶() {
        VoxelithRuntimeConfig config = config();
        Path first = config.modelsCacheFile(
                VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "file-jar-a"));
        Path second = config.modelsCacheFile(
                VoxelithRuntimeConfig.modelsCacheKey("1.20.1", "file-jar-a"));
        Path otherPack = config.modelsCacheFile(
                VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "file-jar-b"));

        assertTrue(first.startsWith(config.modelsDir()), "分桶必须落在 models 根下：" + first);
        assertEquals(2, config.modelsDir().relativize(first).getNameCount(), "桶名只应剩一层目录");
        assertTrue(!first.equals(second), "版本不同必须分桶：" + first);
        assertTrue(!first.equals(otherPack), "资源包不同必须分桶：" + first);
        // 同一个包重传会拿到新 id：宁可多采一次，也不要复用别的包的模型
        assertTrue(!VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "x")
                .equals(VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "y")));
    }

    /**
     * 模组 jar 必须参与采集缓存分桶：models.json.gz 是「带 mod 烘出来的模型全表」，
     * 换模组复用旧桶会让新模组的方块静默缺几何。空列表与老两参口径一致（已有缓存桶继续命中）。
     */
    @Test
    void 模组参与采集缓存分桶() {
        String noMods = VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "file-jar-a");
        String withMods = VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "file-jar-a", List.of("mod-1"));
        String differentMods = VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "file-jar-a", List.of("mod-2"));

        assertTrue(!noMods.equals(withMods), "有模组和没模组必须分桶");
        assertTrue(!withMods.equals(differentMods), "模组不同必须分桶");
        assertEquals(noMods, VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "file-jar-a", List.of()),
                "空模组列表应与老口径一致，旧缓存桶才能继续命中");
        // 叠加顺序也参与摘要：mod 之间的覆盖顺序不同，烘焙结果可能不同
        assertTrue(!VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "file-jar-a", List.of("m1", "m2"))
                .equals(VoxelithRuntimeConfig.modelsCacheKey("1.21.1", "file-jar-a", List.of("m2", "m1"))),
                "模组顺序不同必须分桶");
    }

    @Test
    void 采集产物查找与分桶规则一致() {        VoxelithRuntimeConfig config = config();
        // 桶还不存在：判不出「现成产物」
        assertTrue(config.resolveModelsFile("1.21.1", null, "jar-a") == null,
                "桶不存在应返回 null，由调用方决定采集");
        // 显式配置优先
        VoxelithRuntimeConfig explicit = new VoxelithRuntimeConfig(
                Path.of("C:/java/bin/java.exe"), Path.of("C:/runtime"),
                null, List.of(), Path.of("D:/cache/models.json.gz"),
                4096, 180, 1, false, 0, 2048, true);
        assertTrue(explicit.resolveModelsFile("1.21.1", "client-1.21.1.jar", "jar-a") == null,
                "显式配置的文件不存在时返回 null（不能退回共享缓存，会串味）");
    }

    @Test
    void 超时与并发数被规范到有效区间() {
        VoxelithRuntimeConfig config = new VoxelithRuntimeConfig(
                Path.of("C:/java/bin/java.exe"), Path.of("C:/runtime"), null, List.of(), null,
                0, 0, 0, false, 0, 0, true);
        assertEquals(VoxelithRuntimeConfig.DEFAULT_TIMEOUT_MINUTES, config.timeoutMinutes());
        assertEquals(1, config.maxConcurrentTasks());
        assertEquals(VoxelithRuntimeConfig.DEFAULT_SIDECAR_HEAP_MIB, config.sidecarHeapMiB());
    }

    /**
     * 回归（2026-09-25 线上故障「渲染失败」）：
     *
     * <p>运行根写成相对路径（默认就是 {@code data/voxelith-map}）时，子进程会用<b>它自己的工作
     * 目录</b>去解析我们下发的相对路径。宿主日志里的形态是
     * {@code -Xlog:gc:file=data\voxelith-map\tasks\render-task-xxx\render-gc.log} 打不开日志、
     * JVM 直接拒绝启动（连 main 都没进）；同一根因还会让 {@code -cp} 指到不存在的 fat jar，
     * 表现为 {@code 找不到或无法加载主类 ...PropertiesLauncher}。所以这些路径必须在宿主侧
     * 就绝对化。</p>
     */
    @Test
    void 相对路径一律绝对化() {
        VoxelithRuntimeConfig relative = new VoxelithRuntimeConfig(
                Path.of("tools/java/bin/java.exe"),
                Path.of("data/voxelith-map"),
                Path.of("engine/voxelith-server.jar"),
                List.of("engine/runtime-worker.jar", " "),
                Path.of("cache/models.json.gz"),
                4096, 180, 1, false, 0, 2048, true);

        Path root = Path.of("data/voxelith-map").toAbsolutePath().normalize();

        assertEquals(root, relative.runtimeRoot());
        assertEquals(root.resolve("tasks"), relative.tasksDir());
        // engine-jar / worker-classpath / models-file 是独立设置项，按宿主工作目录解析（不是运行根）
        assertTrue(relative.externalServerJar().startsWith(Path.of("").toAbsolutePath().normalize()),
                "外部引擎 jar 必须绝对：" + relative.externalServerJar());
        assertTrue(relative.externalServerJar().endsWith(Path.of("engine/voxelith-server.jar")));
        assertEquals(1, relative.externalWorkerClasspath().size(), "空白项应被丢弃");
        assertTrue(Path.of(relative.externalWorkerClasspath().get(0)).isAbsolute());
        for (Path path : List.of(relative.javaExecutable(), relative.runtimeDir(), relative.tasksDir(),
                relative.workRoot(), relative.publishRoot(), relative.panoramaDir(), relative.queueDir(),
                relative.modelsDir(), relative.provisionCacheDir(), relative.modelsCacheFile("1.21.1-abcd"),
                relative.mapWorkDir("m"), relative.sidecarDir())) {
            assertTrue(path.isAbsolute(), "交给子进程的路径必须绝对：" + path);
        }

        // 渲染子进程的 GC 日志落在任务目录里，且必须以运行根为前缀
        Path gcLog = relative.tasksDir().resolve("render-task-1").resolve("render-gc.log");
        assertTrue(gcLog.isAbsolute() && gcLog.startsWith(root), "GC 日志路径异常：" + gcLog);
    }
}
