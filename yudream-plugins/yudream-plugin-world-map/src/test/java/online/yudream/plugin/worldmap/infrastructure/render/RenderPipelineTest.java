package online.yudream.plugin.worldmap.infrastructure.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.plugin.worldmap.infrastructure.world.anvil.ChunkFixtures;
import online.yudream.plugin.worldmap.infrastructure.world.anvil.MCAWriter;
import online.yudream.plugin.worldmap.infrastructure.world.anvil.RegionFile;
import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtTag;
import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtWriter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染管线端到端测试：程序化生成最小世界（草地 + 石头 + 水 + 玻璃），
 * 用真实 1.21.4 客户端 jar（target/test-cache 缓存，下载失败则跳过）渲染两个 tile。
 *
 * <p>世界布局：</p>
 * <ul>
 *   <li>chunk (0,0)：y=0 全草地，y=1 有水潭（2..3,2..3）、玻璃 (5,5)、并排石头 (8,8),(9,8) → tile (0,0)</li>
 *   <li>chunk (2,0)：仅两个并排石头 (36,5,4),(37,5,4) → tile (1,0)，用于面剔除断言</li>
 * </ul>
 */
class RenderPipelineTest {

    private static final String CLIENT_URL = "https://bmclapi2.bangbang93.com/version/1.21.4/client";
    private static final Path CACHE = Path.of("target", "test-cache", "client-1.21.4.jar");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    static Path worldDir;

    private static Path clientJar;
    private static CollectingSink sink;
    private static RenderSummary summary;
    private static List<String> progressMessages;

    record TilePos(int tx, int tz) {
    }

    record LodTilePos(int lod, int tx, int tz) {
    }

    /** 收集渲染产物的内存 sink。 */
    static final class CollectingSink implements TileSink {
        final Map<TilePos, byte[]> hires = new HashMap<>();
        final Map<LodTilePos, byte[]> lowres = new HashMap<>();
        byte[] atlas;

        @Override
        public void putHiresTile(int tx, int tz, byte[] gzipJson) {
            hires.put(new TilePos(tx, tz), gzipJson);
        }

        @Override
        public void putLowresTile(int lod, int tx, int tz, byte[] png) {
            lowres.put(new LodTilePos(lod, tx, tz), png);
        }

        @Override
        public void putAtlas(byte[] png) {
            atlas = png;
        }
    }

    @BeforeAll
    static void renderOnce() throws Exception {
        clientJar = ensureClientJar();
        Assumptions.assumeTrue(clientJar != null, "客户端 jar 下载失败，跳过渲染集成测试（网络不可达？）");
        buildWorld();

        sink = new CollectingSink();
        progressMessages = new ArrayList<>();
        int[] lastDone = {-1};
        int[] lastTotal = {-1};
        WorldMapRenderer renderer = new DefaultWorldMapRenderer();
        summary = renderer.render(
                new RenderJob(worldDir, clientJar, "overworld", 0, 0, 1, 0, false),
                sink,
                (done, total, message) -> {
                    lastDone[0] = done;
                    lastTotal[0] = total;
                    progressMessages.add(message);
                });
        assertEquals(2, lastTotal[0], "total = hires tile 数");
        assertEquals(2, lastDone[0], "结束时 done 应等于 total");
    }

    // ---------- hires 端到端 ----------

    @Test
    void hires字段齐全且长度一致() throws Exception {
        byte[] gz = sink.hires.get(new TilePos(0, 0));
        assertNotNull(gz, "tile (0,0) 应产出");
        JsonNode json = MAPPER.readTree(gunzip(gz));
        String[] fields = {"x", "z", "positions", "indices", "uvs", "colors", "ao", "blocklight", "skylight"};
        for (String field : fields) {
            assertTrue(json.has(field), "缺少字段 " + field);
        }
        assertEquals(0, json.get("x").asInt());
        assertEquals(0, json.get("z").asInt());

        int vertices = json.get("positions").size() / 3;
        assertTrue(vertices > 0, "应有顶点");
        assertEquals(0, json.get("positions").size() % 3);
        assertEquals(vertices * 2, json.get("uvs").size(), "uvs 与顶点数不一致");
        assertEquals(vertices * 3, json.get("colors").size(), "colors 与顶点数不一致");
        assertEquals(vertices, json.get("ao").size(), "ao 与顶点数不一致");
        assertEquals(vertices, json.get("blocklight").size(), "blocklight 与顶点数不一致");
        assertEquals(vertices, json.get("skylight").size(), "skylight 与顶点数不一致");
        assertEquals(0, json.get("indices").size() % 6, "indices 应为 6 的倍数（每面 2 三角形）");

        int maxIndex = 0;
        for (JsonNode idx : json.get("indices")) {
            maxIndex = Math.max(maxIndex, idx.asInt());
        }
        assertTrue(maxIndex < vertices, "索引越界");

        // ao = AO × 面方向明暗 ∈ [0.275,1]（最低：0.55 AO × 0.5 底面），光照 ∈ [0,15]，露天面 skylight 应达 15
        int maxSky = 0;
        boolean tintedGreen = false;
        for (int i = 0; i < vertices; i++) {
            float ao = (float) json.get("ao").get(i).asDouble();
            assertTrue(ao >= 0.27f && ao <= 1.0f, "ao 越界: " + ao);
            int bl = json.get("blocklight").get(i).asInt();
            int sl = json.get("skylight").get(i).asInt();
            assertTrue(bl >= 0 && bl <= 15, "blocklight 越界: " + bl);
            assertTrue(sl >= 0 && sl <= 15, "skylight 越界: " + sl);
            maxSky = Math.max(maxSky, sl);
            float r = (float) json.get("colors").get(i * 3).asDouble();
            float g = (float) json.get("colors").get(i * 3 + 1).asDouble();
            if (g > r && g < 1.0f) {
                tintedGreen = true; // 草地顶面群系染色生效
            }
        }
        assertEquals(15, maxSky, "露天面应有满天空光");
        assertTrue(tintedGreen, "草地顶面应有群系绿色染色");
    }

    @Test
    void 并排石头之间不产出相向面() throws Exception {
        // tile (1,0) 内仅有两个并排石头：各 6 面，剔除 2 个相向面 → 10 面 = 40 顶点
        byte[] gz = sink.hires.get(new TilePos(1, 0));
        assertNotNull(gz, "tile (1,0) 应产出");
        JsonNode json = MAPPER.readTree(gunzip(gz));
        assertEquals(1, json.get("x").asInt());
        assertEquals(0, json.get("z").asInt());
        assertEquals(40 * 3, json.get("positions").size(),
                "两个并排石头应剔除 2 个相向面，剩 10 面 40 顶点");
        assertEquals(10 * 6, json.get("indices").size());
    }

    @Test
    void 空tile不产出() {
        assertEquals(2, summary.hiresTiles(), "两个 tile 均有内容");
    }

    // ---------- lowres ----------

    @Test
    void lowres金字塔尺寸与透明度() throws Exception {
        byte[] lod0 = sink.lowres.get(new LodTilePos(0, 0, 0));
        assertNotNull(lod0, "lod0 tile (0,0) 应产出");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(lod0));
        assertEquals(512, img.getWidth());
        assertEquals(512, img.getHeight());

        int opaque = 0;
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) {
                    opaque++;
                }
            }
        }
        // 16×16 草地 + 2 石头柱 = 258，允许少量浮动
        assertTrue(opaque > 200, "lod0 非透明像素过少: " + opaque);
        assertEquals(0, img.getRGB(300, 300) >>> 24, "未生成区域应透明");

        // 草地像素应偏绿（群系染色）
        int grass = img.getRGB(0, 0);
        assertTrue(((grass >> 8) & 0xFF) >= ((grass >> 16) & 0xFF), "草地顶色应偏绿: " + Integer.toHexString(grass));

        // 上级 lod 均应产出（范围塌缩为单 tile）
        for (int lod = 1; lod <= 4; lod++) {
            byte[] lodN = sink.lowres.get(new LodTilePos(lod, 0, 0));
            assertNotNull(lodN, "lod" + lod + " 应产出");
            BufferedImage imgN = ImageIO.read(new ByteArrayInputStream(lodN));
            assertEquals(512, imgN.getWidth());
            assertEquals(512, imgN.getHeight());
        }
        assertEquals(5, summary.lowresTiles());
    }

    // ---------- atlas ----------

    @Test
    void 贴图集已输出() throws Exception {
        assertNotNull(sink.atlas);
        assertTrue(sink.atlas.length > 10_000);
        assertEquals(sink.atlas.length, summary.atlasBytes());
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(sink.atlas));
        assertTrue(img.getWidth() >= 256);
    }

    // ---------- 世界装置 ----------

    private static void buildWorld() throws Exception {
        // chunk (0,0)：草地 + 水 + 玻璃 + 石头
        List<NbtTag> palette = List.of(
                ChunkFixtures.paletteEntry("minecraft:air"),                                   // 0
                ChunkFixtures.paletteEntry("minecraft:stone"),                                 // 1
                ChunkFixtures.paletteEntry("minecraft:grass_block", Map.of("snowy", "false")), // 2
                ChunkFixtures.paletteEntry("minecraft:water", Map.of("level", "0")),           // 3
                ChunkFixtures.paletteEntry("minecraft:glass"));                                // 4
        int[] blocks = new int[4096];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                blocks[ChunkFixtures.blockIndex(lx, 0, lz)] = 2; // y=0 草地
            }
        }
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                blocks[ChunkFixtures.blockIndex(2 + dx, 1, 2 + dz)] = 3; // 水潭
            }
        }
        blocks[ChunkFixtures.blockIndex(5, 1, 5)] = 4; // 玻璃
        blocks[ChunkFixtures.blockIndex(8, 1, 8)] = 1; // 并排石头
        blocks[ChunkFixtures.blockIndex(9, 1, 8)] = 1;
        byte[] skyLight = new byte[2048];
        Arrays.fill(skyLight, (byte) 0xFF);
        NbtTag chunk00 = ChunkFixtures.chunk(0, 0,
                ChunkFixtures.section(0, palette, blocks, null, skyLight, List.of("minecraft:plains"), null));

        // chunk (2,0)：仅两个并排石头（世界坐标 (36,5,4),(37,5,4)）
        int[] pairBlocks = new int[4096];
        pairBlocks[ChunkFixtures.blockIndex(4, 5, 4)] = 1;
        pairBlocks[ChunkFixtures.blockIndex(5, 5, 4)] = 1;
        NbtTag chunk20 = ChunkFixtures.chunk(2, 0,
                ChunkFixtures.section(0,
                        List.of(ChunkFixtures.paletteEntry("minecraft:air"),
                                ChunkFixtures.paletteEntry("minecraft:stone")),
                        pairBlocks, null, skyLight, List.of("minecraft:plains"), null));

        Path regionDir = worldDir.resolve("region");
        Files.createDirectories(regionDir);
        MCAWriter.writeRegion(regionDir.resolve("r.0.0.mca"), List.of(
                new MCAWriter.Entry(0, 0, RegionFile.COMPRESSION_ZLIB, zlib(NbtWriter.write("", chunk00)), 1000),
                new MCAWriter.Entry(2, 0, RegionFile.COMPRESSION_ZLIB, zlib(NbtWriter.write("", chunk20)), 1001)));
    }

    private static byte[] zlib(byte[] raw) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(bos)) {
            z.write(raw);
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    /** 下载（带缓存）客户端 jar，失败返回 null。 */
    private static Path ensureClientJar() {
        try {
            if (Files.exists(CACHE) && Files.size(CACHE) > 1_000_000) {
                return CACHE;
            }
            Files.createDirectories(CACHE.getParent());
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(CLIENT_URL))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(CACHE));
            if (response.statusCode() == 200 && Files.size(CACHE) > 1_000_000) {
                return CACHE;
            }
            System.err.println("[RenderPipelineTest] 下载状态异常: " + response.statusCode());
            return null;
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            System.err.println("[RenderPipelineTest] 下载失败: " + e);
            return null;
        }
    }
}
