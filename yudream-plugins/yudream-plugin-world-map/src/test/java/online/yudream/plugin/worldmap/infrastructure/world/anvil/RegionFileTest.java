package online.yudream.plugin.worldmap.infrastructure.world.anvil;

import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtTag;
import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Anvil region 文件：location/timestamp 表、zlib/gzip chunk 读取。 */
class RegionFileTest {

    @TempDir
    Path dir;

    private static byte[] zlib(byte[] raw) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(bos)) {
            z.write(raw);
        }
        return bos.toByteArray();
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        return bos.toByteArray();
    }

    @Test
    void readsLocationTimestampAndChunks() throws Exception {
        NbtTag chunkA = ChunkFixtures.chunk(0, 0,
                ChunkFixtures.section(0, List.of(ChunkFixtures.paletteEntry("minecraft:air")), null,
                        null, null, List.of("minecraft:plains"), null));
        NbtTag chunkB = ChunkFixtures.chunk(5, 3,
                ChunkFixtures.section(1, List.of(ChunkFixtures.paletteEntry("minecraft:air")), null,
                        null, null, List.of("minecraft:plains"), null));

        Path mca = dir.resolve("r.0.0.mca");
        MCAWriter.writeRegion(mca, List.of(
                new MCAWriter.Entry(0, 0, RegionFile.COMPRESSION_ZLIB,
                        zlib(NbtWriter.write("", chunkA)), 111111),
                new MCAWriter.Entry(5, 3, RegionFile.COMPRESSION_GZIP,
                        gzip(NbtWriter.write("", chunkB)), 222222)));

        try (RegionFile region = RegionFile.open(mca)) {
            assertTrue(region.hasChunk(0, 0));
            assertTrue(region.hasChunk(5, 3));
            assertFalse(region.hasChunk(1, 1));
            assertEquals(111111, region.timestamp(0, 0));
            assertEquals(222222, region.timestamp(5, 3));
            assertEquals(0, region.timestamp(1, 1));

            NbtTag a = region.readChunk(0, 0);
            assertEquals(3465, a.getInt("DataVersion", 0));
            assertEquals(0, a.getInt("xPos", -1));

            NbtTag b = region.readChunk(5, 3);
            assertEquals(5, b.getInt("xPos", -1));
            assertEquals(3, b.getInt("zPos", -1));
            // 第二个 section 的 Y=1
            assertEquals(1, b.get("sections").asList().get(0).getByte("Y", (byte) -1));

            assertNull(region.readChunk(2, 2));
        }
    }

    @Test
    void openToleratesTruncatedFile() throws Exception {
        Path mca = dir.resolve("r.0.0.mca");
        java.nio.file.Files.write(mca, new byte[100]); // 不足 8KiB 头部
        try (RegionFile region = RegionFile.open(mca)) {
            assertFalse(region.hasChunk(0, 0));
            assertNull(region.readChunk(0, 0));
        }
    }
}
