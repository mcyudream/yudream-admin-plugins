package online.yudream.plugin.voxelithmap.infrastructure.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldArchiveExtractor#streamJarEntries(Path, JarEntrySink)}：实例 mods 目录
 * 整包 zip 的流式解包（逐条回调，不整包进内存）。
 */
class WorldArchiveExtractorTest {

    /** 把若干「条目名 → 内容」写成 zip 字节。 */
    private static byte[] zipOf(Object... nameThenContent) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) nameThenContent[i]));
                Object content = nameThenContent[i + 1];
                if (content instanceof byte[] bytes) {
                    zip.write(bytes);
                }
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /** 收集回调到的条目，便于断言。 */
    private static List<String> namesOf(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        WorldArchiveExtractor.streamJarEntries(zip, (name, content) -> names.add(name));
        return names;
    }

    /** 模拟 mcpanel 侧打包结果：mods 目录整包，混有目录条目与非 jar 文件。 */
    @Test
    void extractsOnlyJarEntriesAndStripsDirectories(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("mods.zip");
        Files.write(zip, zipOf(
                "mods/", new byte[0],
                "mods/JEI.jar", "jei-bytes".getBytes(StandardCharsets.UTF_8),
                "mods/sodium.jar", "sodium-bytes".getBytes(StandardCharsets.UTF_8),
                "mods/config.toml", "not-a-mod".getBytes(StandardCharsets.UTF_8)));
        List<String> names = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        WorldArchiveExtractor.streamJarEntries(zip, (name, content) -> {
            names.add(name);
            contents.add(new String(content, StandardCharsets.UTF_8));
        });
        assertEquals(List.of("JEI.jar", "sodium.jar"), names);
        assertEquals(List.of("jei-bytes", "sodium-bytes"), contents);
    }

    /** 保留 zip 内出现顺序：渲染按「先绑先叠」消费模组，顺序即覆盖优先级。 */
    @Test
    void preservesEntryOrder(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("mods.zip");
        Files.write(zip, zipOf("b.jar", new byte[]{2}, "a.jar", new byte[]{1}));
        assertEquals(List.of("b.jar", "a.jar"), namesOf(zip));
    }

    /** 空 mods 目录（只有目录条目）按零模组处理，不算错误。 */
    @Test
    void emptyModsDirectoryYieldsNoJars(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("mods.zip");
        Files.write(zip, zipOf("mods/", new byte[0]));
        assertTrue(namesOf(zip).isEmpty());
    }

    /**
     * 不是合法 zip（比如误传了单个 jar 改名成 zip）要报出能看懂的错误。
     *
     * <p>这条是必需的：ZipInputStream 遇到非 zip 字节会静默返回零条目，若不先校验魔数，
     * 一次误传就会被当成「空 mods 目录」悄悄放过去，模组静默丢失。</p>
     */
    @Test
    void rejectsInvalidZipWithReadableMessage(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("not-a-zip.zip");
        Files.write(zip, "definitely not a zip".getBytes(StandardCharsets.UTF_8));
        IOException e = assertThrows(IOException.class, () -> namesOf(zip));
        assertTrue(e.getMessage().contains("模组包不是合法的 zip"));
    }

    /** 主流程 extract 的 zip-slip 防护回归。 */
    @Test
    void extractRejectsZipSlipPaths(@TempDir Path destination) throws IOException {
        Path archive = destination.resolve("evil.zip");
        Files.write(archive, zipOf("../evil.txt", "boom".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class,
                () -> WorldArchiveExtractor.extract(archive, destination.resolve("out")));
    }

    /**
     * 多维度存档包：按目录名定位各自维度的存档根（实例导入一个包建多张地图的基础）。
     *
     * <p>一个包里同时存在 {@code world/}、{@code world_nether/}、{@code world_the_end/} 三份
     * {@code level.dat}，通用推断无法区分（只会挑到最浅的那份），必须靠 rootHint 精确落位。</p>
     */
    @Test
    void extractsTheRequestedDimensionDirectory(@TempDir Path dir) throws IOException {
        Path archive = dir.resolve("worlds.zip");
        Files.write(archive, zipOf(
                "world/level.dat", "overworld-level".getBytes(StandardCharsets.UTF_8),
                "world_nether/level.dat", "nether-level".getBytes(StandardCharsets.UTF_8),
                "world_the_end/level.dat", "end-level".getBytes(StandardCharsets.UTF_8)));

        Path nether = WorldArchiveExtractor.extract(archive, dir.resolve("out"), "world_nether");
        assertEquals("world_nether", nether.getFileName().toString());
        assertEquals("nether-level", Files.readString(nether.resolve("level.dat")));

        // 提示与包内容对不上时回退自动判定（历史数据里 worldName 可能是地图名），不能报错打断渲染
        Path fallback = WorldArchiveExtractor.extract(archive, dir.resolve("out2"), "某个地图名");
        assertTrue(Files.isRegularFile(fallback.resolve("level.dat")));
    }

    /**
     * 老版布局（{@code world/DIM-1}、{@code world/DIM1}）：按维度归一到新版形状。
     *
     * <p>老版的下界/末地塞在 world 里面且**没有 level.dat**；不归一会有两个后果：下界/末地建不出图，
     * 以及主世界把这些 DIM 目录里的 region 也算进自己的范围（预览图大片空白）。</p>
     */
    @Test
    void normalisesLegacyNestedDimensionsIntoModernDirectories(@TempDir Path dir) throws IOException {
        Path archive = dir.resolve("legacy.zip");
        Files.write(archive, zipOf(
                "world/level.dat", "world-level".getBytes(StandardCharsets.UTF_8),
                "world/region/r.0.0.mca", "overworld-region".getBytes(StandardCharsets.UTF_8),
                "world/DIM-1/region/r.0.0.mca", "nether-region".getBytes(StandardCharsets.UTF_8),
                "world/DIM1/region/r.0.0.mca", "end-region".getBytes(StandardCharsets.UTF_8)));

        // 下界：DIM-1 的内容归一到 world_nether，并补一份 level.dat（DIM 目录自己没有版本信息）
        Path nether = WorldArchiveExtractor.extract(archive, dir.resolve("nether"),
                "world_nether", "minecraft:nether");
        assertEquals("world_nether", nether.getFileName().toString());
        assertEquals("nether-region", Files.readString(nether.resolve("region/r.0.0.mca")));
        assertEquals("world-level", Files.readString(nether.resolve("level.dat")),
                "补的 level.dat 来自同一个世界：缺了它引擎的版本体检与预览的存档编目都会失明");
        assertTrue(Files.notExists(nether.getParent().resolve("world_the_end")), "别的维度不跟着进来");

        // 末地：DIM1 的内容归一到 world_the_end
        Path end = WorldArchiveExtractor.extract(archive, dir.resolve("end"),
                "world_the_end", "minecraft:the_end");
        assertEquals("world_the_end", end.getFileName().toString());
        assertEquals("end-region", Files.readString(end.resolve("region/r.0.0.mca")));

        // 主世界：留下自己的 region，**丢掉** world/DIM-1 与 world/DIM1
        Path overworld = WorldArchiveExtractor.extract(archive, dir.resolve("overworld"),
                "world", "minecraft:overworld");
        assertEquals("overworld-region", Files.readString(overworld.resolve("region/r.0.0.mca")));
        assertTrue(Files.notExists(overworld.resolve("DIM-1")), "嵌套维度目录必须从主世界里去掉");
        assertTrue(Files.notExists(overworld.resolve("DIM1")), "嵌套维度目录必须从主世界里去掉");
    }

    /** 不指定维度时不动任何东西：老版包原样解开，由通用推断挑存档根（历史数据里的 worldName 可能是地图名）。 */
    @Test
    void leavesLegacyLayoutAloneWhenNoDimensionIsKnown(@TempDir Path dir) throws IOException {
        Path archive = dir.resolve("legacy.zip");
        Files.write(archive, zipOf(
                "world/level.dat", "world-level".getBytes(StandardCharsets.UTF_8),
                "world/DIM-1/region/r.0.0.mca", "nether-region".getBytes(StandardCharsets.UTF_8)));

        Path root = WorldArchiveExtractor.extract(archive, dir.resolve("out"), "某个地图名");
        assertEquals("world", root.getFileName().toString());
        assertTrue(Files.isRegularFile(root.resolve("DIM-1/region/r.0.0.mca")),
                "维度未知时保持原样，不能把数据丢掉");
    }
}
