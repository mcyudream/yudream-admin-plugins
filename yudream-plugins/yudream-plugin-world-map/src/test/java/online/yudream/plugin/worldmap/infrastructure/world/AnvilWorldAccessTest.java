package online.yudream.plugin.worldmap.infrastructure.world;

import online.yudream.plugin.worldmap.infrastructure.world.anvil.ChunkFixtures;
import online.yudream.plugin.worldmap.infrastructure.world.anvil.MCAWriter;
import online.yudream.plugin.worldmap.infrastructure.world.anvil.RegionFile;
import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtTag;
import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WorldAccess 端到端测试：程序化生成 .mca 装置，断言解析结果与写入一致。
 * 覆盖不同调色板位宽（5bit / 4bit / 单色调色板）、双光源、生物群系、
 * 负坐标 region、维度目录映射与未生成区块默认值。
 */
class AnvilWorldAccessTest {

    @TempDir
    Path worldDir;

    @BeforeEach
    void buildWorld() throws Exception {
        // ---------- overworld：region/r.0.0.mca，chunk (0,0) zlib ----------
        // section Y=-1：20 项调色板 → 5 bit 位宽
        List<NbtTag> palette5bit = List.of(
                ChunkFixtures.paletteEntry("minecraft:air"),                                  // 0
                ChunkFixtures.paletteEntry("minecraft:stone"),                                // 1
                ChunkFixtures.paletteEntry("minecraft:dirt"),                                 // 2
                ChunkFixtures.paletteEntry("minecraft:oak_log", Map.of("axis", "y")),         // 3
                ChunkFixtures.paletteEntry("minecraft:grass_block", Map.of("snowy", "false")),// 4
                ChunkFixtures.paletteEntry("minecraft:sand"),                                 // 5
                ChunkFixtures.paletteEntry("minecraft:gravel"),                               // 6
                ChunkFixtures.paletteEntry("minecraft:glass"),                                // 7
                ChunkFixtures.paletteEntry("minecraft:oak_leaves"),                           // 8
                ChunkFixtures.paletteEntry("minecraft:water"),                                // 9
                ChunkFixtures.paletteEntry("minecraft:lava"),                                 // 10
                ChunkFixtures.paletteEntry("minecraft:bedrock"),                              // 11
                ChunkFixtures.paletteEntry("minecraft:iron_ore"),                             // 12
                ChunkFixtures.paletteEntry("minecraft:coal_ore"),                             // 13
                ChunkFixtures.paletteEntry("minecraft:gold_ore"),                             // 14
                ChunkFixtures.paletteEntry("minecraft:diamond_ore"),                          // 15
                ChunkFixtures.paletteEntry("minecraft:redstone_ore"),                         // 16
                ChunkFixtures.paletteEntry("minecraft:lapis_ore"),                            // 17
                ChunkFixtures.paletteEntry("minecraft:emerald_ore"),                          // 18
                ChunkFixtures.paletteEntry("minecraft:copper_ore"));                          // 19

        int[] blocks = new int[4096];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                blocks[ChunkFixtures.blockIndex(lx, 0, lz)] = 1; // y=-16 石头地板
            }
        }
        for (int ly = 1; ly < 16; ly++) {
            blocks[ChunkFixtures.blockIndex(5, ly, 7)] = 3; // (5, -15..-1, 7) 橡木原木柱
        }
        blocks[ChunkFixtures.blockIndex(0, 15, 0)] = 2;     // (0, -1, 0) 泥土

        byte[] blockLight = new byte[2048];
        ChunkFixtures.setNibble(blockLight, ChunkFixtures.blockIndex(5, 10, 7), 15); // 奇数索引高位
        ChunkFixtures.setNibble(blockLight, ChunkFixtures.blockIndex(0, 0, 0), 3);   // 偶数索引低位

        byte[] skyLight = new byte[2048];
        Arrays.fill(skyLight, (byte) 0xFF);
        ChunkFixtures.setNibble(skyLight, ChunkFixtures.blockIndex(0, 0, 0), 0);     // (0,-16,0) 无天空光

        // 群系：2 项调色板 → 1 bit；(bx=1,by=1,bz=1) 即世界 x/z 4..7、y -12..-9 为沙漠
        int[] biomes = new int[64];
        biomes[ChunkFixtures.biomeIndex(1, 1, 1)] = 1;

        NbtTag sectionM1 = ChunkFixtures.section(-1, palette5bit, blocks, blockLight, skyLight,
                List.of("minecraft:plains", "minecraft:desert"), biomes);

        // section Y=0：2 项调色板 → 4 bit；(5,5,7) 玻璃；无 BlockLight；无 biomes
        int[] blocks0 = new int[4096];
        blocks0[ChunkFixtures.blockIndex(5, 5, 7)] = 1;
        byte[] skyLight0 = new byte[2048];
        Arrays.fill(skyLight0, (byte) 0xFF);
        NbtTag section0 = ChunkFixtures.section(0,
                List.of(ChunkFixtures.paletteEntry("minecraft:air"),
                        ChunkFixtures.paletteEntry("minecraft:glass")),
                blocks0, null, skyLight0, null, null);

        // section Y=1：单色调色板，无 data / 无光照 / 单色群系
        NbtTag section1 = ChunkFixtures.section(1,
                List.of(ChunkFixtures.paletteEntry("minecraft:air")), null,
                null, null, List.of("minecraft:plains"), null);

        NbtTag chunk00 = ChunkFixtures.chunk(0, 0, sectionM1, section0, section1);

        // chunk (3,1) gzip：section Y=0，(48,0,16) 水；无 SkyLight 数组（测默认 15）
        int[] blocks31 = new int[4096];
        blocks31[ChunkFixtures.blockIndex(0, 0, 0)] = 1;
        int[] biomes31 = new int[64];
        biomes31[ChunkFixtures.biomeIndex(0, 0, 0)] = 1; // x 48..51, y 0..3, z 16..19 森林
        NbtTag chunk31 = ChunkFixtures.chunk(3, 1,
                ChunkFixtures.section(0,
                        List.of(ChunkFixtures.paletteEntry("minecraft:air"),
                                ChunkFixtures.paletteEntry("minecraft:water")),
                        blocks31, null, null,
                        List.of("minecraft:plains", "minecraft:forest"), biomes31));

        Path regionDir = worldDir.resolve("region");
        Files.createDirectories(regionDir);
        MCAWriter.writeRegion(regionDir.resolve("r.0.0.mca"), List.of(
                new MCAWriter.Entry(0, 0, RegionFile.COMPRESSION_ZLIB, zlib(NbtWriter.write("", chunk00)), 1000),
                new MCAWriter.Entry(3, 1, RegionFile.COMPRESSION_GZIP, gzip(NbtWriter.write("", chunk31)), 1001)));

        // ---------- 负坐标：region/r.-1.0.mca，chunk (-2,0) → 局部 (30,0) ----------
        int[] blocksNeg = new int[4096];
        blocksNeg[ChunkFixtures.blockIndex(0, 0, 0)] = 1; // 世界 (-32, 0, 0) 基岩
        NbtTag chunkNeg = ChunkFixtures.chunk(-2, 0,
                ChunkFixtures.section(0,
                        List.of(ChunkFixtures.paletteEntry("minecraft:air"),
                                ChunkFixtures.paletteEntry("minecraft:bedrock")),
                        blocksNeg, null, null, List.of("minecraft:plains"), null));
        MCAWriter.writeRegion(regionDir.resolve("r.-1.0.mca"), List.of(
                new MCAWriter.Entry(30, 0, RegionFile.COMPRESSION_ZLIB, zlib(NbtWriter.write("", chunkNeg)), 1002)));

        // ---------- nether：DIM-1/region/r.0.0.mca，chunk (0,0) ----------
        int[] blocksNether = new int[4096];
        blocksNether[ChunkFixtures.blockIndex(1, 0, 1)] = 1; // (1,0,1) 下界岩
        NbtTag chunkNether = ChunkFixtures.chunk(0, 0,
                ChunkFixtures.section(0,
                        List.of(ChunkFixtures.paletteEntry("minecraft:air"),
                                ChunkFixtures.paletteEntry("minecraft:netherrack")),
                        blocksNether, null, null, List.of("minecraft:nether_wastes"), null));
        Path netherRegion = worldDir.resolve("DIM-1").resolve("region");
        Files.createDirectories(netherRegion);
        MCAWriter.writeRegion(netherRegion.resolve("r.0.0.mca"), List.of(
                new MCAWriter.Entry(0, 0, RegionFile.COMPRESSION_ZLIB, zlib(NbtWriter.write("", chunkNether)), 1003)));
    }

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
    void blockStatesAcrossBitWidths() throws Exception {
        try (WorldAccess world = WorldLoader.load(worldDir, "overworld")) {
            // 5 bit 调色板（20 项）
            assertEquals(new BlockState("minecraft:stone", Map.of()), world.blockState(8, -16, 8));
            assertEquals(new BlockState("minecraft:oak_log", Map.of("axis", "y")), world.blockState(5, -10, 7));
            assertEquals(new BlockState("minecraft:dirt", Map.of()), world.blockState(0, -1, 0));
            // 4 bit 调色板（2 项）
            assertEquals(new BlockState("minecraft:glass", Map.of()), world.blockState(5, 5, 7));
            // 单色调色板 section
            assertEquals(BlockState.AIR, world.blockState(0, 16, 0));
            // gzip 压缩的 chunk (3,1)
            assertEquals(new BlockState("minecraft:water", Map.of()), world.blockState(48, 0, 16));
            // 负坐标 region r.-1.0.mca
            assertEquals(new BlockState("minecraft:bedrock", Map.of()), world.blockState(-32, 0, 0));
            // 普通空气
            assertEquals(BlockState.AIR, world.blockState(5, 6, 7));
        }
    }

    @Test
    void missingBlocksReturnAir() throws Exception {
        try (WorldAccess world = WorldLoader.load(worldDir, "overworld")) {
            assertEquals(BlockState.AIR, world.blockState(-33, 0, 0));      // region 内未生成 chunk
            assertEquals(BlockState.AIR, world.blockState(1000, 64, 1000)); // region 文件不存在
            assertEquals(BlockState.AIR, world.blockState(5, 100, 7));      // 超出已生成 section 范围
            assertEquals(BlockState.AIR, world.blockState(5, -100, 7));
        }
    }

    @Test
    void lightsFollowWrittenData() throws Exception {
        try (WorldAccess world = WorldLoader.load(worldDir, "overworld")) {
            assertEquals(15, world.blockLight(5, -6, 7));   // 奇数索引高位 nibble
            assertEquals(3, world.blockLight(0, -16, 0));   // 偶数索引低位 nibble
            assertEquals(0, world.blockLight(1, -16, 0));
            assertEquals(0, world.blockLight(5, 5, 7));     // section 无 BlockLight → 默认 0
            assertEquals(0, world.skyLight(0, -16, 0));     // 写入的 0
            assertEquals(15, world.skyLight(0, -15, 0));    // 0xFF
            assertEquals(15, world.skyLight(48, 0, 16));    // 无 SkyLight 数组 → 默认 15
            assertEquals(15, world.skyLight(1000, 64, 1000)); // 未生成 → 默认 15
            assertEquals(0, world.blockLight(1000, 64, 1000));
        }
    }

    @Test
    void biomesFollowWrittenData() throws Exception {
        try (WorldAccess world = WorldLoader.load(worldDir, "overworld")) {
            assertEquals("minecraft:desert", world.biome(5, -10, 5));  // by=1 范围
            assertEquals("minecraft:plains", world.biome(5, -13, 5));  // by=0
            assertEquals("minecraft:plains", world.biome(5, 5, 7));    // section 无 biomes → 默认
            assertEquals("minecraft:forest", world.biome(49, 1, 17));  // gzip chunk 的群系
            assertEquals("minecraft:plains", world.biome(1000, 64, 1000));
        }
    }

    @Test
    void maxYAndHeightRange() throws Exception {
        try (WorldAccess world = WorldLoader.load(worldDir, "overworld")) {
            // 触发 chunk (0,0) 加载，高度范围应由 section Y=-1..1 推导
            world.blockState(5, -10, 7);
            assertEquals(-16, world.minY());
            assertEquals(32, world.maxBuildY());

            assertEquals(5, world.maxY(5, 7));    // 玻璃柱顶
            assertEquals(-1, world.maxY(0, 0));   // 泥土
            assertEquals(-16, world.maxY(8, 8));  // 仅石头地板
            assertEquals(0, world.maxY(48, 16));  // 水面
            // 空柱（chunk (3,1) 内全空气位置）→ minY
            assertEquals(world.minY(), world.maxY(49, 17));
            // 未生成区块 → minY
            assertEquals(world.minY(), world.maxY(1000, 1000));
        }
    }

    @Test
    void dimensionDirectories() throws Exception {
        try (WorldAccess nether = WorldLoader.load(worldDir, "nether")) {
            assertEquals(new BlockState("minecraft:netherrack", Map.of()), nether.blockState(1, 0, 1));
            assertEquals("minecraft:nether_wastes", nether.biome(1, 0, 1));
            assertEquals(0, nether.minY());
            assertEquals(16, nether.maxBuildY());
        }
        // minecraft: 前缀
        try (WorldAccess overworld = WorldLoader.load(worldDir, "minecraft:overworld")) {
            assertEquals(new BlockState("minecraft:glass", Map.of()), overworld.blockState(5, 5, 7));
        }
        // 维度目录不存在（the_end 未建）→ 全部默认
        try (WorldAccess end = WorldLoader.load(worldDir, "the_end")) {
            assertEquals(BlockState.AIR, end.blockState(0, 64, 0));
            assertEquals(15, end.skyLight(0, 64, 0));
            assertEquals(-64, end.minY());
            assertEquals(320, end.maxBuildY());
        }
        assertThrows(IllegalArgumentException.class, () -> WorldLoader.load(worldDir, "candy"));
    }
}
