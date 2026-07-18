package online.yudream.plugin.worldmap.infrastructure.resource;

import online.yudream.plugin.worldmap.infrastructure.world.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单测：不依赖网络，用合成客户端 jar 走完整 zip 加载路径。
 */
class ResourcePacksTest {

    private static final float EPS = 1e-4f;

    private static BlockModelRegistry registry;

    @BeforeAll
    static void setUp() throws IOException {
        Path dir = Path.of("target", "test-cache", "synth");
        Path jar = SynthClientJar.create(dir);
        registry = ResourcePacks.load(jar);
    }

    private static BakedQuad[] quads(String name, Map<String, String> props) {
        return registry.quadsFor(new BlockState(name, props));
    }

    /** 找出 y 坐标恒为给定值的面（顶面/底面）。 */
    private static BakedQuad quadWithConstantAxis(BakedQuad[] quads, int axis, float value) {
        for (BakedQuad q : quads) {
            boolean match = true;
            for (int i = 0; i < 4; i++) {
                if (Math.abs(q.positions()[i * 3 + axis] - value) > EPS) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return q;
            }
        }
        return null;
    }

    /** 断言 quad 的 uv 全部落在给定 rect 内。 */
    private static void assertUvInRect(BakedQuad quad, TextureAtlas.UVRect rect) {
        for (int i = 0; i < 8; i += 2) {
            float u = quad.uvs()[i];
            float v = quad.uvs()[i + 1];
            assertTrue(u >= rect.u0() - EPS && u <= rect.u1() + EPS,
                    "u 越界: " + u + " 不在 [" + rect.u0() + "," + rect.u1() + "]");
            assertTrue(v >= rect.v0() - EPS && v <= rect.v1() + EPS,
                    "v 越界: " + v + " 不在 [" + rect.v0() + "," + rect.v1() + "]");
        }
    }

    @Test
    void 父链合并与纹理变量链_子覆盖生效() {
        BakedQuad[] quads = quads("test_cube", Map.of());
        assertEquals(3, quads.length, "test_cube 应有 3 个面");
        // 子类把 base 覆盖为 test_derived，chained→#base 也应指向 test_derived
        assertNotNull(registry.atlas().uv("block/test_derived"));
        assertNull(registry.atlas().uv("block/test_base"), "被覆盖的父纹理不应进入贴图集");
    }

    @Test
    void 面属性_cullface_tint_uv旋转() {
        BakedQuad[] quads = quads("test_cube", Map.of());
        TextureAtlas.UVRect rect = registry.atlas().uv("block/test_derived");

        // faces 声明顺序：north, up, east
        BakedQuad north = quads[0];
        assertEquals("north", north.cullface());
        for (int i = 0; i < 4; i++) {
            assertEquals(0f, north.positions()[i * 3 + 2], EPS, "north 面 z 应全为 0");
        }
        assertUvInRect(north, rect);

        BakedQuad up = quads[1];
        assertEquals(TintType.GRASS, up.tint(), "tintindex:0 + 非水/叶纹理 → GRASS");
        assertEquals(16f, up.positions()[1], EPS);

        // east 面 uv [0,0,8,8] + rotation 90：角点指派顺时针旋 1 步 → A 点拿到 (u1,v2)=(0,8)
        BakedQuad east = quads[2];
        assertEquals(rect.mapU(0), east.uvs()[0], EPS);
        assertEquals(rect.mapV(8), east.uvs()[1], EPS);
        assertEquals(rect.mapU(8), east.uvs()[2], EPS);
    }

    @Test
    void blockstate旋转_原木三轴() {
        TextureAtlas.UVRect endRect = registry.atlas().uv("block/test_end");

        BakedQuad[] axisY = quads("test_log", Map.of("axis", "y"));
        BakedQuad top = quadWithConstantAxis(axisY, 1, 16f);
        assertNotNull(top, "axis=y 应有 y=16 的顶面");
        assertUvInRect(top, endRect);
        assertEquals("up", top.cullface());

        // x=90（右手系 -90）：+y → -z，顶面变北面，cullface 同步旋转
        BakedQuad[] axisZ = quads("test_log", Map.of("axis", "z"));
        BakedQuad northTop = quadWithConstantAxis(axisZ, 2, 0f);
        assertNotNull(northTop, "axis=z 时原顶面应转到 z=0");
        assertUvInRect(northTop, endRect);
        assertEquals("north", northTop.cullface(), "cullface 应随 blockstate 旋转");

        // x=90 再 y=90：+y → +x，顶面变东面
        BakedQuad[] axisX = quads("test_log", Map.of("axis", "x"));
        BakedQuad eastTop = quadWithConstantAxis(axisX, 0, 16f);
        assertNotNull(eastTop, "axis=x 时原顶面应转到 x=16");
        assertUvInRect(eastTop, endRect);
        assertEquals("east", eastTop.cullface(), "cullface 应随 blockstate 旋转");
    }

    @Test
    void multipart条件合并() {
        assertEquals(1, quads("test_fence", Map.of()).length, "仅命中基础部分");
        assertEquals(2, quads("test_fence", Map.of("north", "true")).length);
        assertEquals(2, quads("test_fence", Map.of("east", "true")).length, "OR 第一支命中");
        assertEquals(2, quads("test_fence", Map.of("south", "true")).length, "OR 第二支命中");
        assertEquals(2, quads("test_fence", Map.of("type", "b")).length, "| 多值命中");
        assertEquals(1, quads("test_fence", Map.of("type", "c")).length, "| 多值未命中");
    }

    @Test
    void multipart无命中_返回空而非品红() {
        assertEquals(0, quads("test_multi_empty", Map.of()).length);
    }

    @Test
    void variants子集匹配_省略属性回退空键() {
        // 1.20.2+：blockstate 仅 "" 键时，带任意属性的状态都应命中
        assertEquals(3, quads("test_subset", Map.of()).length);
        assertEquals(3, quads("test_subset", Map.of("distance", "3", "persistent", "false")).length);
    }

    @Test
    void 缺失模型回退品红立方体() {
        // 引用的模型不存在
        BakedQuad[] broken = quads("test_broken", Map.of());
        assertEquals(6, broken.length);
        for (BakedQuad q : broken) {
            assertNull(q.cullface(), "缺失立方体不做 cullface");
            assertUvInRect(q, registry.atlas().uv(TextureAtlas.MISSING_TEXTURE));
        }
        // blockstate 本身不存在
        assertEquals(6, quads("no_such_block", Map.of()).length);
        // variants 键值与状态冲突（无任何子集匹配）→ 非法状态
        assertEquals(6, quads("test_conflict", Map.of("axis", "y")).length);
        // 多余未知属性按子集匹配被忽略（1.20.2+ 语义），不算缺失
        assertEquals(3, quads("test_cube", Map.of("foo", "bar")).length);
    }

    @Test
    void 合法无面模型_返回空() {
        assertEquals(0, quads("test_air", Map.of()).length);
    }

    @Test
    void 动画贴图取首帧() {
        TextureAtlas atlas = registry.atlas();
        TextureAtlas.UVRect rect = atlas.uv("block/test_anim");
        assertNotNull(rect);
        BufferedImage img = SynthClientJar.decodePng(atlas.png());
        int cx = (int) ((rect.u0() + rect.u1()) / 2 * img.getWidth());
        int cy = (int) ((rect.v0() + rect.v1()) / 2 * img.getHeight());
        int rgb = img.getRGB(cx, cy);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        assertTrue(r > 200 && g < 60 && b < 60,
                "首帧应为红色，实际 rgb=(" + r + "," + g + "," + b + ")");
        // 单格 16px：rect 像素宽高都应为 16
        assertEquals(16f, (rect.u1() - rect.u0()) * img.getWidth(), 1f);
        assertEquals(16f, (rect.v1() - rect.v0()) * img.getHeight(), 1f);
    }

    @Test
    void 半透明标记() {
        BakedQuad glassy = quads("test_glassy", Map.of())[0];
        assertTrue(glassy.translucent(), "含半透明像素 → translucent");
        BakedQuad water = quads("test_water", Map.of())[0];
        assertTrue(water.translucent(), "水纹理名 → translucent");
        assertEquals(TintType.WATER, water.tint());
        BakedQuad solid = quads("test_cube", Map.of())[1];
        assertTrue(!solid.translucent(), "不透明纹理 → 非 translucent");
    }

    @Test
    void 液体方块注入内置几何() {
        // 静水 level=0：6 面，顶面高度 16*8/9，WATER 染色，半透明
        BakedQuad[] still = quads("water", Map.of("level", "0"));
        assertEquals(6, still.length);
        BakedQuad top = quadWithConstantAxis(still, 1, 8 * 16f / 9f);
        assertNotNull(top, "静水顶面高度应为 16*8/9");
        assertEquals(TintType.WATER, top.tint());
        assertTrue(top.translucent());
        assertUvInRect(top, registry.atlas().uv("block/water_still"));
        // 流动水 level=7：高度 16/9
        assertNotNull(quadWithConstantAxis(quads("water", Map.of("level", "7")), 1, 16f / 9f));
        // falling / level>=8：满格
        assertNotNull(quadWithConstantAxis(quads("water", Map.of("level", "8")), 1, 16f));
    }

    @Test
    void uvlock修正() {
        TextureAtlas.UVRect rect = registry.atlas().uv("block/test_end");
        BakedQuad off = quads("test_uvlock_off", Map.of())[0];
        BakedQuad on = quads("test_uvlock_on", Map.of())[0];
        // 同一模型 y=90：uvlock 开启后 up 面 uv 指派应整体旋转
        assertNotEquals(off.uvs()[0], on.uvs()[0], 1e-6f, "uvlock 应改变 uv 指派");
        // off: A 点 (0,0)；on: 顺时针补 3 步 → A 点 (8,0)
        assertEquals(rect.mapU(0), off.uvs()[0], EPS);
        assertEquals(rect.mapU(8), on.uvs()[0], EPS);
        assertEquals(rect.mapV(0), on.uvs()[1], EPS);
    }

    @Test
    void 元素级旋转与rescale() {
        BakedQuad plain = quads("test_rot", Map.of())[0];
        BakedQuad rescaled = quads("test_rot_rescale", Map.of())[0];
        float maxAbsPlain = maxAbsOffset(plain);
        float maxAbsRescaled = maxAbsOffset(rescaled);
        // 45° 旋转后角点偏移 = 6·√2 ≈ 8.485（超出原 2..14 盒）
        assertTrue(maxAbsPlain > 8.4f && maxAbsPlain < 8.6f, "旋转后偏移应约 8.485，实际 " + maxAbsPlain);
        // rescale 再乘 √2 → 12
        assertTrue(maxAbsRescaled > 11.9f && maxAbsRescaled < 12.1f, "rescale 后偏移应约 12，实际 " + maxAbsRescaled);
    }

    /** 顶点相对方块中心 (8,8,8) 在 xz 平面上的最大偏移。 */
    private static float maxAbsOffset(BakedQuad quad) {
        float max = 0;
        for (int i = 0; i < 4; i++) {
            max = Math.max(max, Math.abs(quad.positions()[i * 3] - 8));
            max = Math.max(max, Math.abs(quad.positions()[i * 3 + 2] - 8));
        }
        return max;
    }

    @Test
    void 生物群系染色() {
        DefaultBlockModelRegistry impl = (DefaultBlockModelRegistry) registry;
        assertArrayEquals(new int[]{0x12, 0x34, 0x56}, impl.biomeColors().grassColor("minecraft:plains"));
        assertArrayEquals(new int[]{0x12, 0x34, 0x56}, impl.biomeColors().grassColor("plains"), "前缀可选");
        assertArrayEquals(new int[]{0x65, 0x43, 0x21}, impl.biomeColors().foliageColor("minecraft:plains"));
        // 未知群系回退平原采样点
        assertArrayEquals(new int[]{0x12, 0x34, 0x56}, impl.biomeColors().grassColor("minecraft:no_such_biome"));
        // 缺 colormap 时回退默认色
        BiomeColors empty = new BiomeColors(null, null);
        assertEquals(3, empty.grassColor("plains").length);
    }

    @Test
    void 全部uv都在01区间() {
        String[][] states = {
                {"test_cube"}, {"test_log"}, {"test_fence"}, {"test_animcube"},
                {"test_glassy"}, {"test_water"}, {"test_uvlock_on"}, {"test_broken"}
        };
        for (String[] s : states) {
            Map<String, String> props = s[0].equals("test_log") ? Map.of("axis", "y") : Map.of();
            for (BakedQuad q : quads(s[0], props)) {
                for (float uv : q.uvs()) {
                    assertTrue(uv >= -EPS && uv <= 1 + EPS, s[0] + " 的 uv 越界: " + uv);
                }
            }
        }
    }

    @Test
    void 贴图集非空且布局合理() {
        TextureAtlas atlas = registry.atlas();
        byte[] png = atlas.png();
        assertTrue(png.length > 100, "贴图集 PNG 不应为空");
        BufferedImage img = SynthClientJar.decodePng(png);
        // 全部纹理 16px → 格 16 + 2*1px padding = stride 18，宽高都应为 stride 的倍数
        int stride = TextureAtlas.BASE_CELL + TextureAtlas.PADDING * 2;
        assertTrue(img.getWidth() > 0 && img.getWidth() % stride == 0,
                "宽度应为 stride(" + stride + ") 的倍数，实际 " + img.getWidth());
        assertTrue(img.getHeight() > 0 && img.getHeight() % stride == 0,
                "高度应为 stride(" + stride + ") 的倍数，实际 " + img.getHeight());
    }
}
