package online.yudream.plugin.worldmap.infrastructure.resource;

import online.yudream.plugin.worldmap.infrastructure.world.BlockState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试：从 BMCLAPI 下载 1.21.4 原版客户端 jar，走完整加载路径。
 * 网络不可达 / 下载失败时通过 {@link Assumptions} 跳过。
 */
class ClientJarIntegrationTest {

    private static final String CLIENT_URL = "https://bmclapi2.bangbang93.com/version/1.21.4/client";
    private static final Path CACHE = Path.of("target", "test-cache", "client-1.21.4.jar");
    private static final float EPS = 1e-4f;

    private static BlockModelRegistry registry;

    @BeforeAll
    static void setUp() {
        Path jar = ensureClientJar();
        Assumptions.assumeTrue(jar != null, "客户端 jar 下载失败，跳过集成测试（网络不可达？）");
        registry = ResourcePacks.load(jar);
        Assumptions.assumeTrue(registry != null);
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
                    .followRedirects(HttpClient.Redirect.NORMAL) // bmclapi 返回 302
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(CLIENT_URL))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(CACHE));
            if (response.statusCode() == 200 && Files.size(CACHE) > 1_000_000) {
                return CACHE;
            }
            System.err.println("[ClientJarIntegrationTest] 下载状态异常: " + response.statusCode());
            return null;
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            System.err.println("[ClientJarIntegrationTest] 下载失败: " + e);
            return null;
        }
    }

    @Test
    void 代表性方块模型非空() {
        assertTrue(registry.quadsFor(new BlockState("minecraft:stone", Map.of())).length > 0, "stone");
        assertTrue(registry.quadsFor(new BlockState("minecraft:oak_log", Map.of("axis", "y"))).length > 0, "oak_log y");
        assertTrue(registry.quadsFor(new BlockState("oak_log", Map.of("axis", "x"))).length > 0, "oak_log x 可省略前缀");
        assertTrue(registry.quadsFor(new BlockState("minecraft:water", Map.of("level", "0"))).length > 0, "water");
        assertTrue(registry.quadsFor(new BlockState("minecraft:grass_block", Map.of("snowy", "false"))).length > 0, "grass_block");
        assertTrue(registry.quadsFor(new BlockState("minecraft:oak_stairs",
                Map.of("facing", "east", "half", "bottom", "shape", "straight"))).length > 0, "oak_stairs");
        assertTrue(registry.quadsFor(new BlockState("minecraft:oak_fence", Map.of(
                "east", "false", "north", "false", "south", "false", "west", "false", "waterlogged", "false"))).length > 0,
                "oak_fence multipart");
        assertTrue(registry.quadsFor(new BlockState("minecraft:glass", Map.of())).length > 0, "glass");
    }

    @Test
    void stone为完整六面立方体() {
        BakedQuad[] quads = registry.quadsFor(new BlockState("minecraft:stone", Map.of()));
        assertEquals(6, quads.length, "stone 是 cube_all，应有 6 面");
        for (BakedQuad q : quads) {
            assertNotNull(q.cullface(), "stone 各面应带 cullface");
            assertEquals(TintType.NONE, q.tint());
        }
    }

    @Test
    void 染色类型符合预期() {
        BakedQuad[] grass = registry.quadsFor(new BlockState("minecraft:grass_block", Map.of("snowy", "false")));
        assertTrue(hasTint(grass, TintType.GRASS), "grass_block 顶面应有 GRASS 染色");
        BakedQuad[] leaves = registry.quadsFor(new BlockState("minecraft:oak_leaves",
                Map.of("distance", "1", "persistent", "false")));
        assertTrue(leaves.length > 0 && hasTint(leaves, TintType.FOLIAGE), "oak_leaves 应有 FOLIAGE 染色");
        BakedQuad[] water = registry.quadsFor(new BlockState("minecraft:water", Map.of("level", "0")));
        assertTrue(hasTint(water, TintType.WATER), "water 应有 WATER 染色");
    }

    private static boolean hasTint(BakedQuad[] quads, TintType tint) {
        for (BakedQuad q : quads) {
            if (q.tint() == tint) {
                return true;
            }
        }
        return false;
    }

    @Test
    void 贴图集尺寸与uv区间() {
        TextureAtlas atlas = registry.atlas();
        byte[] png = atlas.png();
        assertTrue(png.length > 10_000, "贴图集 PNG 体积异常小: " + png.length);
        BufferedImage img = SynthClientJar.decodePng(png);
        assertTrue(img.getWidth() >= 256 && img.getWidth() <= 16384, "宽度不合理: " + img.getWidth());
        assertTrue(img.getHeight() >= 256 && img.getHeight() <= 16384, "高度不合理: " + img.getHeight());
        assertTrue(atlas.size() > 500, "1.21.4 引用纹理应远超 500，实际 " + atlas.size());

        // 抽查多个方块：全部 uv 必须落在 [0,1]
        BlockState[] samples = {
                new BlockState("stone", Map.of()),
                new BlockState("oak_log", Map.of("axis", "y")),
                new BlockState("water", Map.of("level", "0")),
                new BlockState("grass_block", Map.of("snowy", "false")),
                new BlockState("oak_stairs", Map.of("facing", "east", "half", "bottom", "shape", "straight")),
                new BlockState("white_wool", Map.of()),
                new BlockState("glass", Map.of())
        };
        for (BlockState state : samples) {
            for (BakedQuad q : registry.quadsFor(state)) {
                for (float uv : q.uvs()) {
                    assertTrue(uv >= -EPS && uv <= 1 + EPS,
                            state.name() + " uv 越界: " + uv);
                }
            }
        }
    }

    @Test
    void 缺失方块回退品红立方体() {
        BakedQuad[] quads = registry.quadsFor(new BlockState("minecraft:no_such_block_xyz", Map.of()));
        assertEquals(6, quads.length);
        TextureAtlas.UVRect missing = registry.atlas().uv(TextureAtlas.MISSING_TEXTURE);
        assertNotNull(missing);
        for (BakedQuad q : quads) {
            for (int i = 0; i < 8; i += 2) {
                assertTrue(q.uvs()[i] >= missing.u0() - EPS && q.uvs()[i] <= missing.u1() + EPS);
                assertTrue(q.uvs()[i + 1] >= missing.v0() - EPS && q.uvs()[i + 1] <= missing.v1() + EPS);
            }
        }
    }

    @Test
    void 空气返回空面() {
        assertEquals(0, registry.quadsFor(new BlockState("minecraft:air", Map.of())).length);
    }

    @Test
    void 生物群系染色合理() {
        DefaultBlockModelRegistry impl = (DefaultBlockModelRegistry) registry;
        int[] plains = impl.biomeColors().grassColor("minecraft:plains");
        assertTrue(plains[1] > plains[0] && plains[1] > plains[2],
                "平原草色应偏绿: rgb=(" + plains[0] + "," + plains[1] + "," + plains[2] + ")");
        int[] desert = impl.biomeColors().grassColor("minecraft:desert");
        assertNotEquals(plains[0] + plains[1] * 256 + plains[2] * 65536,
                desert[0] + desert[1] * 256 + desert[2] * 65536, "沙漠与平原草色应不同");
        int[] foliage = impl.biomeColors().foliageColor("minecraft:forest");
        assertTrue(foliage[1] >= foliage[0], "森林叶色应偏绿");
    }
}
