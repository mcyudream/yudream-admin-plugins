package online.yudream.plugin.voxelithmap.infrastructure.modpack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模组包完整性排查。
 *
 * <p>用例对着线上那次真实失败写：模组包装了 {@code t_and_t}，它依赖 {@code cristellib}，而
 * cristellib 不在包里——Fabric 引导于是拒绝启动、采集退回静态解析，模组方块大面积缺几何。
 * 排查要在导入阶段就把「t_and_t 需要 cristellib」讲清楚，用户才不会渲染完才发现。</p>
 *
 * <p>夹具必须写成**真的 jar**：模组 jar 本身是 zip，元数据（{@code fabric.mod.json} /
 * {@code META-INF/mods.toml}）在它内部；把 JSON 直接当 jar 内容写，排查器只会判定为坏包。</p>
 */
class ModPackInspectorTest {

    private final ModPackInspector inspector = new ModPackInspector(new ObjectMapper());

    /** 造一个 zip（模组 jar 与模组包都是 zip，只是装的条目不同）。 */
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 带 Fabric 元数据的模组 jar。 */
    private static byte[] fabricJar(String id, String extra) throws IOException {
        String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\"" + extra + "}";
        return zip(Map.of("fabric.mod.json", text(json)));
    }

    /** 带 Forge 元数据的模组 jar。 */
    private static byte[] forgeJar(String modsToml) throws IOException {
        return zip(Map.of("META-INF/mods.toml", text(modsToml)));
    }

    private static Path pack(Path dir, Map<String, byte[]> entries) throws IOException {
        Path archive = dir.resolve("mods.zip");
        Files.write(archive, zip(entries));
        return archive;
    }

    @Test
    void reportsDependencyMissingFromThePack(@TempDir Path dir) throws IOException {
        Path archive = pack(dir, Map.of(
                "t_and_t-1.12.jar", fabricJar("t_and_t", ",\"depends\":{\"cristellib\":\">=1.0\",\"minecraft\":\"1.20.1\"}"),
                "sushi-mod-2.7.0.jar", fabricJar("youkaishomecoming", "")));

        ModPackInspector.Report report = inspector.inspect(archive);

        assertEquals(2, report.jarCount());
        assertTrue(report.hasMissing(), "缺少 cristellib 必须被查出来");
        assertEquals(List.of("t_and_t 需要 cristellib"), report.missingSummary());
        // 报告要给出可操作的下一步
        String text = String.join("\n", ModPackInspector.describe(report));
        assertTrue(text.contains("t_and_t 需要 cristellib"), text);
        assertTrue(text.contains("重新导入"), text);
    }

    @Test
    void satisfiesDependenciesViaProvidedAliases(@TempDir Path dir) throws IOException {
        // 这个 jar 自己的 id 是 cristellib_impl，但 provides 里声明了 cristellib → 依赖被满足
        Path archive = pack(dir, Map.of(
                "t_and_t-1.12.jar", fabricJar("t_and_t", ",\"depends\":{\"cristellib\":\"*\"}"),
                "lib-1.0.jar", fabricJar("cristellib_impl", ",\"provides\":[\"cristellib\"]")));

        ModPackInspector.Report report = inspector.inspect(archive);

        assertFalse(report.hasMissing(), report.missingSummary().toString());
    }

    @Test
    void ignoresPlatformDependenciesAndOptionalOnes(@TempDir Path dir) throws IOException {
        Path archive = pack(dir, Map.of(
                "a.jar", fabricJar("moda", ""),
                "b.jar", fabricJar("modb", ",\"depends\":{\"moda\":\"*\",\"fabricloader\":\">=0.14\",\"java\":\">=17\"}"),
                "readme.txt", text("not a jar")));

        ModPackInspector.Report report = inspector.inspect(archive);

        assertEquals(2, report.jarCount());
        assertFalse(report.hasMissing(), report.missingSummary().toString());
        assertEquals(1, ModPackInspector.describe(report).size(), "干净包只该有一行汇总");
    }

    @Test
    void readsForgeModsTomlAndHonoursMandatoryFlag(@TempDir Path dir) throws IOException {
        String toml = """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"

                [[mods]]
                modId="verdantvibes"
                version="1.0.3"

                [[dependencies.verdantvibes]]
                    modId="minecraft"
                    mandatory=true
                    versionRange="[1.20.1,1.21)"
                [[dependencies.verdantvibes]]
                    modId="framework"
                    mandatory=true
                    versionRange="[1.0,)"
                [[dependencies.verdantvibes]]
                    modId="jei"
                    mandatory=false
                    versionRange="[15,)"
                """;
        Path archive = pack(dir, Map.of(
                "verdantvibes-1.0.3.jar", forgeJar(toml),
                "framework-1.0.jar", forgeJar("modLoader=\"javafml\"\n[[mods]]\nmodId=\"framework\"\n")));

        ModPackInspector.Report report = inspector.inspect(archive);

        // framework 在包里 → 满足；jei 是 mandatory=false → 不报；minecraft 是平台依赖 → 不报
        assertFalse(report.hasMissing(), report.missingSummary().toString());
        assertTrue(ModPackInspector.describe(report).get(0).contains("可识别模组元数据 2 个"),
                ModPackInspector.describe(report).toString());
    }

    @Test
    void reportsMissingMandatoryForgeDependency(@TempDir Path dir) throws IOException {
        String toml = """
                modLoader="javafml"

                [[mods]]
                modId="verdantvibes"

                [[dependencies.verdantvibes]]
                    modId="cristellib"
                    mandatory=true
                """;
        Path archive = pack(dir, Map.of("verdantvibes-1.0.3.jar", forgeJar(toml)));

        ModPackInspector.Report report = inspector.inspect(archive);

        assertTrue(report.hasMissing());
        assertEquals(List.of("verdantvibes 需要 cristellib"), report.missingSummary());
    }

    /**
     * 解压不出来的 jar = 包被截断/损坏 → {@code usable() == false}，导入端据此不登记模组包。
     *
     * <p>正是线上那次：模组包在打包/传输中被截断，渲染解包时报
     * {@code Unexpected end of ZLIB input stream}。这里用一个压缩方法非法的 jar 头复现同一判定。</p>
     */
    /** 只读中央目录列出包内 jar：名称 + 大小，按名排序，非 jar 与目录不出现。 */
    @Test
    void listsJarEntriesWithoutDecompressing(@TempDir Path dir) throws IOException {
        Path archive = pack(dir, Map.of(
                "z-last.jar", fabricJar("z", ""),
                "a-first.jar", fabricJar("a", ""),
                "notes.txt", text("not a jar")));

        List<ModPackInspector.JarInfo> jars = ModPackInspector.listJars(archive, 100);

        assertEquals(List.of("a-first.jar", "z-last.jar"), jars.stream().map(ModPackInspector.JarInfo::name).toList());
        assertTrue(jars.stream().allMatch(jar -> jar.sizeBytes() > 0), "应带上解压后字节数");
    }

    /** 超大包只返回前 N 条，但总数仍要准（界面据此提示「仅列出前 N 个」）。 */
    @Test
    void capsListingAtTheRequestedLimit(@TempDir Path dir) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            entries.put("mod-" + i + ".jar", text("x"));
        }
        Path archive = pack(dir, entries);

        assertEquals(3, ModPackInspector.listJars(archive, 3).size());
    }

    /** 不是 zip（或已损坏）：解析失败要抛 IOException，由调用方转成可读报错。 */
    @Test
    void listingFailsForNonZipArchive(@TempDir Path dir) throws IOException {
        Path notZip = dir.resolve("mods.zip");
        Files.write(notZip, text("definitely not a zip"));

        assertThrows(IOException.class, () -> ModPackInspector.listJars(notZip, 100));
    }

    @Test
    void marksPackUnusableWhenAJarCannotBeDecompressed(@TempDir Path dir) throws IOException {
        byte[] brokenJar = new byte[30];
        brokenJar[0] = 0x50;
        brokenJar[1] = 0x4B;
        brokenJar[2] = 0x03;
        brokenJar[3] = 0x04;   // zip 本地头签名
        brokenJar[8] = 99;     // 压缩方法 99：不存在，读条目即抛

        Path archive = pack(dir, Map.of("broken.jar", brokenJar, "fine.jar", fabricJar("fine", "")));

        ModPackInspector.Report report = inspector.inspect(archive);

        assertFalse(report.usable(), "解压失败必须判为不可用：" + report.corrupt());
        assertEquals(1, report.corrupt().size(), report.corrupt().toString());
        String text = String.join(System.lineSeparator(), ModPackInspector.describe(report));
        assertTrue(text.contains("模组包已损坏"), text);
        assertTrue(text.contains("Unexpected end of ZLIB input stream"), text);
    }

    /** 整个包被截断（尾部缺失）：连中央目录都读不到，读取直接失败——调用方按不可用处理。 */
    @Test
    void truncatedPackFailsToBeInspected(@TempDir Path dir) throws IOException {
        Path archive = pack(dir, Map.of("a.jar", fabricJar("moda", "")));
        byte[] complete = Files.readAllBytes(archive);
        Path truncated = dir.resolve("truncated.zip");
        Files.write(truncated, java.util.Arrays.copyOf(complete, complete.length - 30));

        assertThrows(IOException.class, () -> inspector.inspect(truncated));
    }

    @Test
    void toleratesUnreadableJarsWithoutLosingTheRest(@TempDir Path dir) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("broken.jar", text("this is not a zip"));
        entries.put("fine.jar", fabricJar("fine", ",\"depends\":{\"absent-lib\":\"*\"}"));
        Path archive = pack(dir, entries);

        ModPackInspector.Report report = inspector.inspect(archive);

        assertEquals(2, report.jarCount());
        // 坏包记进 unreadable，但另一个模组的缺失依赖照常报出来
        assertEquals(1, report.unreadable().size());
        assertEquals(List.of("fine 需要 absent-lib"), report.missingSummary());
    }
}
