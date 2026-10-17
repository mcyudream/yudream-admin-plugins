package online.yudream.plugin.voxelithmap.infrastructure.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * level.dat 版本名解析（TAG_String("Name") 启发式扫描）。
 *
 * <p>这条判据决定自动下载哪个版本的原版 client jar 与采集版本，错一次就是整包静态解析
 * 或下载错版本。用手工构造的最小 NBT 钉住行为。</p>
 */
class LevelDatReaderTest {

    @TempDir
    Path dir;

    @Test
    void 从压缩_level_dat_里读出版本名() throws IOException {
        byte[] nbt = nbtWithDataName("1.21.8");
        Path file = dir.resolve("level.dat");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file))) {
            gzip.write(nbt);
        }
        assertEquals("1.21.8", LevelDatReader.readVersionName(file));
    }

    @Test
    void 未压缩的_level_dat_也能读() throws IOException {
        byte[] nbt = nbtWithDataName("1.20.1");
        Path file = dir.resolve("level.dat");
        Files.write(file, nbt);
        assertEquals("1.20.1", LevelDatReader.readVersionName(file));
    }

    @Test
    void name_不是版本号形态时返回_null() throws IOException {
        // Data.Name = "FunnyWorld"（不像版本号）：不能拿它当采集版本
        byte[] nbt = nbtWithDataName("FunnyWorld");
        Path file = dir.resolve("level.dat");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(file))) {
            gzip.write(nbt);
        }
        assertNull(LevelDatReader.readVersionName(file));
    }

    @Test
    void 文件缺失返回_null() {
        assertNull(LevelDatReader.readVersionName(dir.resolve("nope.dat")));
    }

    @Test
    void 空文件返回_null() throws IOException {
        Path file = dir.resolve("empty.dat");
        Files.write(file, new byte[0]);
        assertNull(LevelDatReader.readVersionName(file));
    }

    // ------------------------------------------------------------------
    // 构造最小 NBT：root("") { Data("") { Name="..." } }
    // ------------------------------------------------------------------

    private static byte[] nbtWithDataName(String version) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // root compound，名为空
        out.write(0x0A);
        writeShort(out, 0);
        // Data compound
        out.write(0x0A);
        writeShort(out, 4);
        write(out, "Data".getBytes(StandardCharsets.UTF_8));
        // Name string
        out.write(0x08);
        writeShort(out, 4);
        write(out, "Name".getBytes(StandardCharsets.UTF_8));
        byte[] value = version.getBytes(StandardCharsets.UTF_8);
        writeShort(out, value.length);
        write(out, value);
        // 干扰项：另一个不叫 Name 的字符串（VersionName），扫描必须跳过它
        out.write(0x08);
        writeShort(out, 11);
        write(out, "VersionName".getBytes(StandardCharsets.UTF_8));
        byte[] decoy = "1.12.2".getBytes(StandardCharsets.UTF_8);
        writeShort(out, decoy.length);
        write(out, decoy);
        // Data / root 结束
        out.write(0x00);
        out.write(0x00);
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
