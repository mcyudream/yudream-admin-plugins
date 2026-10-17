package online.yudream.plugin.voxelithmap.infrastructure.engine;

import online.yudream.plugin.voxelithmap.domain.value.RegionWindow;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLI 参数拼装测试。
 *
 * <p>这层逻辑对着的是外部进程的文本协议：参数名写错一个字母，子进程就以退出码 2 直接退出，
 * 而错误只在日志尾巴里出现，排查代价很高。所以这里用「整串全等」而不是包含断言钉死顺序。</p>
 */
class VoxelithCliArgumentsTest {

    private static RenderSettings settings() {
        return new RenderSettings("minecraft:overworld", -64, 2, 0, true, true,
                0, 0, false, "1.20.1", "0.16.14");
    }

    private static RenderSettings togglesOff() {
        return new RenderSettings("minecraft:overworld", 0, 0, 0, false, false,
                50_000, 2048, true, "1.20.1", "0.16.14");
    }

    private static VoxelithCliArguments.RenderInvocation invocation(RegionWindow region,
                                                                   RenderSettings settings,
                                                                   List<String> workerClasspath) {
        return new VoxelithCliArguments.RenderInvocation(
                Path.of("C:/tmp/world"), "minecraft:overworld", "gen001", "我的世界",
                List.of(Path.of("C:/tmp/client.jar")),
                Path.of("C:/tmp/work"), Path.of("C:/tmp/publish"),
                Path.of("C:/tmp/models.json.gz"), settings, region, workerClasspath);
    }

    @Test
    void 渲染参数顺序与上游一致() {
        List<String> args = VoxelithCliArguments.renderMap(invocation(null, settings(), List.of()));
        assertEquals(List.of(
                "--world-dir", "C:\\tmp\\world",
                "--dimension", "minecraft:overworld",
                "--map-id", "gen001",
                "--map-name", "我的世界",
                "--packs", "C:\\tmp\\client.jar",
                "--work-dir", "C:\\tmp\\work",
                "--publish-dir", "C:\\tmp\\publish",
                "--models-file", "C:\\tmp\\models.json.gz",
                "--max-level", "2",
                "--sample-chunks", "0",
                "--min-y", "-64",
                "--mc-version", "1.20.1",
                "--loader-version", "0.16.14"), args);
    }

    /**
     * 回归（voxelith@4200402）：采集版本留空 = 自动。
     *
     * <p>上游把「资源包文件名 → 存档版本 → 兜底」的判定做进了引擎，而插件此前无条件下发
     * {@code --mc-version 1.20.1}——那等于把自动判定整个关掉，任何非 1.20.1 的存档都会因为
     * 贴图改名（1.20.3 起 {@code grass → short_grass}）渲染出整片品红。留空时必须一个字都不传，
     * 让引擎自己去判。</p>
     */
    @Test
    void 采集版本留空时不下发mcVersion() {
        RenderSettings auto = new RenderSettings("minecraft:overworld", -64, 2, 0, true, true,
                0, 0, false, "", "0.16.14");
        List<String> args = VoxelithCliArguments.renderMap(invocation(null, auto, List.of()));
        assertTrue(!args.contains("--mc-version"),
                "留空 = 交给引擎自动判定，不该下发 --mc-version：" + args);
        assertTrue(args.contains("--loader-version"), "其余参数不应受影响");
    }

    @Test
    void 有裁剪范围时输出四个边界() {
        List<String> args = VoxelithCliArguments.renderMap(
                invocation(new RegionWindow(-512, -256, 511, 255), settings(), List.of()));
        int index = args.indexOf("--min-x");
        assertTrue(index > 0, "应含 --min-x");
        assertEquals(List.of("--min-x", "-512", "--max-x", "511", "--min-z", "-256", "--max-z", "255"),
                args.subList(index, index + 8));
    }

    @Test
    void 关闭开关输出否定参数() {
        List<String> args = VoxelithCliArguments.renderMap(invocation(null, togglesOff(), List.of()));
        assertTrue(args.contains("--no-lod-atlas"), "关闭图集应输出 --no-lod-atlas");
        assertTrue(args.contains("--no-meshopt"), "关闭网格压缩应输出 --no-meshopt");
        assertTrue(args.contains("--skip-harvest"), "跳过采集应输出 --skip-harvest");
        int maxChunks = args.indexOf("--max-chunks");
        assertEquals(List.of("--max-chunks", "50000", "--batch-chunks", "2048"),
                args.subList(maxChunks, maxChunks + 4));
    }

    @Test
    void workerClasspath可重复出现() {
        List<String> args = VoxelithCliArguments.renderMap(invocation(null, settings(),
                List.of("C:/tmp/worker.jar", "C:/tmp/gson.jar")));
        int first = args.indexOf("--worker-classpath");
        assertEquals(List.of("--worker-classpath", "C:/tmp/worker.jar",
                        "--worker-classpath", "C:/tmp/gson.jar"),
                args.subList(first, first + 4));
    }

    @Test
    void 命令行用PropertiesLauncher挂fatJar() {
        List<String> command = VoxelithCliArguments.command(
                Path.of("C:/java/bin/java.exe"), Path.of("C:/tmp/server.jar"),
                VoxelithCliArguments.RENDER_MAIN, 4096, List.of(), List.of("--map-id", "gen001"));
        assertEquals("C:\\java\\bin\\java.exe", command.get(0));
        assertTrue(command.contains("-Xmx4096m"), "应带堆上限");
        assertTrue(command.contains("-Dloader.main=" + VoxelithCliArguments.RENDER_MAIN),
                "应通过 -Dloader.main 指定真实主类");
        int cp = command.indexOf("-cp");
        assertEquals("C:\\tmp\\server.jar", command.get(cp + 1));
        assertTrue(command.contains(VoxelithBundledRuntime.propertiesLauncherClass()),
                "必须用 PropertiesLauncher：-jar 会固定走 JarLauncher");
        assertEquals("--map-id", command.get(command.size() - 2));
    }

    @Test
    void 全景参数齐全() {
        List<String> args = VoxelithCliArguments.panorama(new VoxelithCliArguments.PanoramaInvocation(
                Path.of("C:/tmp/work"), Path.of("C:/tmp/pano"), "map01",
                3840, 2160, 90, 55, 70, 1.5, "panorama.png"));
        assertEquals(List.of(
                "--work-dir", "C:\\tmp\\work",
                "--publish-dir", "C:\\tmp\\pano",
                "--map-id", "map01",
                "--width", "3840",
                "--height", "2160",
                "--yaw", "90.0",
                "--pitch", "55.0",
                "--fov", "70.0",
                "--distance", "1.5",
                "--output", "panorama.png"), args);
    }

    @Test
    void 分片worker的packs是重复参数而不是逗号串() {
        List<String> args = VoxelithCliArguments.shardWorker(new VoxelithCliArguments.ShardWorkerInvocation(
                Path.of("C:/tmp/world"), "minecraft:overworld", "gen001",
                List.of(Path.of("C:/tmp/client.jar"), Path.of("C:/tmp/mod.jar")),
                Path.of("C:/tmp/work"), Path.of("C:/tmp/publish"), Path.of("C:/tmp/models.json.gz"),
                Path.of("C:/tmp/queue"), "worker-1", 4, 30, 10, false));
        int first = args.indexOf("--packs");
        assertEquals(List.of("--packs", "C:\\tmp\\client.jar", "--packs", "C:\\tmp\\mod.jar"),
                args.subList(first, first + 4));
        assertTrue(args.contains("--no-meshopt"), "关闭网格压缩应输出 --no-meshopt");
        int queue = args.indexOf("--queue-dir");
        assertEquals("C:\\tmp\\queue", args.get(queue + 1));
    }

    @Test
    void 分片worker默认值被规范化() {
        VoxelithCliArguments.ShardWorkerInvocation invocation =
                new VoxelithCliArguments.ShardWorkerInvocation(
                        Path.of("w"), "d", "m", null, Path.of("wd"), Path.of("pd"), null,
                        Path.of("q"), "id", 0, 0, 0, true);
        assertEquals(List.of(), invocation.packs(), "packs 为 null 应归一为空列表");
        assertEquals(1, invocation.threads());
        assertEquals(30, invocation.leaseMinutes());
        assertEquals(10, invocation.idleMinutes());
    }
}
