package online.yudream.plugin.voxelithmap.infrastructure.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存档包形态兼容：边车（上游 {@code ZipInputStream}）读不了的包要能被识别并规整。
 *
 * <p>这一条是真实故障的固化：一份第三方工具打的 26MB 存档包，134 个条目<b>全部</b>是
 * 「STORED + 数据描述符」，边车的 {@code WorldStore.unzip} 直接
 * {@code ZipException: only DEFLATED entries can have EXT descriptor}，
 * 管理页只看到「生成预览底图失败」。JDK 自己写不出这种包，只能手拼字节做夹具。</p>
 */
class WorldArchiveNormalizerTest {

    @Test
    void 边车读不了的包会被识别出来(@TempDir Path dir) throws IOException {
        Path archive = withDescriptor(dir.resolve("broken.zip"),
                entries("level.dat", "level", "region/r.0.0.mca", "mca"),
                StandardCharsets.UTF_8, Set.of());

        assertTrue(WorldArchiveNormalizer.needsRepair(archive));
        // 边车踩的就是这里：同样的读法在这个包上直接抛异常
        assertThrows(ZipException.class, () -> readNames(archive, StandardCharsets.UTF_8));
    }

    @Test
    void JDK写出的包不需要规整(@TempDir Path dir) throws IOException {
        Path archive = dir.resolve("plain.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            out.putNextEntry(new ZipEntry("level.dat"));
            out.write("level".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        assertFalse(WorldArchiveNormalizer.needsRepair(archive));
        // 「DEFLATED + 数据描述符」是合法形态，边车读得动——它拒收的只是 STORED + 描述符
        assertEquals(List.of("level.dat"), readNames(archive, StandardCharsets.UTF_8));
    }

    @Test
    void 规整后边车那套读法能读完且内容一致(@TempDir Path dir) throws IOException {
        Path archive = withDescriptor(dir.resolve("broken.zip"),
                entries("world/", "", "world/level.dat", "level-data",
                        "world/region/r.0.0.mca", "region-bytes"),
                StandardCharsets.UTF_8, Set.of());

        Path repaired = WorldArchiveNormalizer.repairInto(archive, dir.resolve("fixed.zip"));

        assertFalse(WorldArchiveNormalizer.needsRepair(repaired));
        assertEquals(List.of("world/", "world/level.dat", "world/region/r.0.0.mca"),
                readNames(repaired, StandardCharsets.UTF_8));
        assertEquals("region-bytes", readEntry(repaired, "world/region/r.0.0.mca", StandardCharsets.UTF_8));
        // 原样直传：本来就存储的条目不该被顺手重新压缩一遍
        assertEquals(ZipEntry.STORED, methodOf(repaired, "world/region/r.0.0.mca"));
    }

    @Test
    void 混合压缩方式的包规整后内容不变(@TempDir Path dir) throws IOException {
        // 真实工具常按内容挑压缩方式，同一个包里两种条目混着来，两条分支都得走对
        Path archive = withDescriptor(dir.resolve("mixed.zip"),
                entries("level.dat", "level-data", "region/r.0.0.mca", "region-bytes"),
                StandardCharsets.UTF_8, Set.of("level.dat"));

        Path repaired = WorldArchiveNormalizer.repairInto(archive, dir.resolve("fixed.zip"));

        assertEquals("level-data", readEntry(repaired, "level.dat", StandardCharsets.UTF_8));
        assertEquals(ZipEntry.DEFLATED, methodOf(repaired, "level.dat"));
        assertEquals("region-bytes", readEntry(repaired, "region/r.0.0.mca", StandardCharsets.UTF_8));
    }

    @Test
    void 条目名经过规整仍能读对(@TempDir Path dir) throws IOException {
        // 存档包里带中文名很常见；规整必须让上游（按系统码页或 UTF-8 标志位解码）读回同一个名字
        Charset source = WorldArchiveNormalizer.entryNameCharset();
        Path archive = withDescriptor(dir.resolve("named.zip"),
                entries("万佛山地形/level.dat", "nbt"), source, Set.of());

        Path repaired = WorldArchiveNormalizer.repairInto(archive, dir.resolve("fixed.zip"));

        assertEquals(List.of("万佛山地形/level.dat"), readNames(repaired, source));
        assertEquals(List.of("万佛山地形/level.dat"), readNames(repaired, StandardCharsets.UTF_8));
    }

    @Test
    void 插件自己的解包器不受这个坑影响(@TempDir Path dir) throws IOException {
        // 渲染走 WorldArchiveExtractor（ZipFile，读中央目录），预览走上游 ZipInputStream。
        // 「同一个包能渲染、不能预览」就是这个差异——钉住它，免得日后被"顺手统一"掉。
        Path archive = withDescriptor(dir.resolve("broken.zip"),
                entries("world/level.dat", "level-data"), StandardCharsets.UTF_8, Set.of());

        Path root = WorldArchiveExtractor.extract(archive, dir.resolve("out"));

        assertEquals("level-data", Files.readString(root.resolve("level.dat")));
    }

    // ---------- 夹具与读取工具 ----------

    /** 按可读顺序拼一组「名字 → 内容」。 */
    private static Map<String, byte[]> entries(String... namesAndContents) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndContents.length; i += 2) {
            result.put(namesAndContents[i], namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    private static List<String> readNames(Path archive, Charset names) throws IOException {
        List<String> result = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), names)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                result.add(entry.getName());
                zip.transferTo(OutputStream.nullOutputStream());
            }
        }
        return result;
    }

    private static String readEntry(Path archive, String name, Charset names) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), names)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("没找到条目: " + name);
    }

    private static int methodOf(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            return zip.getEntry(name).getMethod();
        }
    }

    /**
     * 手写一个条目都带数据描述符的包。
     *
     * <p>形态要点：本地头的长度与 CRC 留空、置 bit 3，数据后面补 16 字节描述符，
     * 真实值只写在中央目录里——正是 JDK 的 {@code ZipInputStream} 拒收（当方法为 STORED 时）、
     * 而 {@code ZipFile} 照读不误的那种包。{@code deflated} 里的条目按 DEFLATED 写，
     * 其余按 STORED。</p>
     */
    private static Path withDescriptor(Path file, Map<String, byte[]> entries, Charset nameCharset,
                                       Set<String> deflated) throws IOException {
        ByteArrayOutputStream bodies = new ByteArrayOutputStream();
        List<byte[]> centrals = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            byte[] name = entry.getKey().getBytes(nameCharset);
            byte[] data = entry.getValue();
            boolean compress = deflated.contains(entry.getKey());
            byte[] payload = compress ? deflate(data) : data;
            int method = compress ? ZipEntry.DEFLATED : ZipEntry.STORED;
            CRC32 crc = new CRC32();
            crc.update(data);
            int offset = bodies.size();

            writeInt(bodies, 0x04034b50);
            writeShort(bodies, 20);
            writeShort(bodies, 0x0008);
            writeShort(bodies, method);
            writeShort(bodies, 0);
            writeShort(bodies, 0);
            writeInt(bodies, 0);
            writeInt(bodies, 0);
            writeInt(bodies, 0);
            writeShort(bodies, name.length);
            writeShort(bodies, 0);
            bodies.write(name);
            bodies.write(payload);
            writeInt(bodies, 0x08074b50);
            writeInt(bodies, (int) crc.getValue());
            writeInt(bodies, payload.length);
            writeInt(bodies, data.length);

            ByteArrayOutputStream central = new ByteArrayOutputStream();
            writeInt(central, 0x02014b50);
            writeShort(central, 20);
            writeShort(central, 20);
            writeShort(central, 0x0008);
            writeShort(central, method);
            writeShort(central, 0);
            writeShort(central, 0);
            writeInt(central, (int) crc.getValue());
            writeInt(central, payload.length);
            writeInt(central, data.length);
            writeShort(central, name.length);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeShort(central, 0);
            writeInt(central, 0);
            writeInt(central, offset);
            central.write(name);
            centrals.add(central.toByteArray());
        }

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        archive.write(bodies.toByteArray());
        int centralOffset = archive.size();
        for (byte[] central : centrals) {
            archive.write(central);
        }
        // 长度必须在写 EOCD 之前算好：EOCD 自身的字节不能计进中央目录长度
        int centralSize = archive.size() - centralOffset;
        writeInt(archive, 0x06054b50);
        writeShort(archive, 0);
        writeShort(archive, 0);
        writeShort(archive, entries.size());
        writeShort(archive, entries.size());
        writeInt(archive, centralSize);
        writeInt(archive, centralOffset);
        writeShort(archive, 0);

        Files.write(file, archive.toByteArray());
        return file;
    }

    /** zip 条目里的 DEFLATE 是**裸流**（不带 zlib 头），所以 nowrap 必须为 true。 */
    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            stream.write(data);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        writeShort(out, value & 0xFFFF);
        writeShort(out, (value >>> 16) & 0xFFFF);
    }
}
