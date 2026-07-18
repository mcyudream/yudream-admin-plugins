package online.yudream.plugin.worldmap.infrastructure.render;

import online.yudream.plugin.worldmap.infrastructure.resource.BakedQuad;
import online.yudream.plugin.worldmap.infrastructure.resource.BlockModelRegistry;
import online.yudream.plugin.worldmap.infrastructure.resource.TintType;
import online.yudream.plugin.worldmap.infrastructure.world.BlockState;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * lowres 顶面颜色采样：按方块状态缓存"顶面纹理平均色 + 染色类型"。
 *
 * <p>面片不携带纹理名，因此直接按面片 uv 包围盒在贴图集图像上求平均 RGB
 * （跳过近全透明 texel）。顶面选取：优先 cullface=up 的面片，
 * 否则取法线朝上的面片，再退化到第一个面片；无面片退化为灰色。</p>
 */
final class TopColorSampler {

    /** 采样结果：平均色（0..1）与染色类型。 */
    record TopSample(float r, float g, float b, TintType tint) {
    }

    /** 无面片方块的退化色（灰）。 */
    private static final TopSample GRAY = new TopSample(0.5f, 0.5f, 0.5f, TintType.NONE);
    /** 低于该 alpha 的 texel 不参与平均（玻璃/树叶空隙）。 */
    private static final int ALPHA_THRESHOLD = 16;

    private final BlockModelRegistry registry;
    private final BufferedImage atlas;
    private final Map<String, TopSample> cache = new HashMap<>();

    TopColorSampler(BlockModelRegistry registry) {
        this.registry = registry;
        this.atlas = decode(registry.atlas().png());
    }

    /**
     * 查询方块顶面采样色。
     *
     * @return 平均色；顶面整体近全透明（如玻璃）时返回 null（调用方输出透明像素）
     */
    TopSample of(BlockState state) {
        return cache.computeIfAbsent(StateOcclusion.key(state), k -> analyze(state));
    }

    private TopSample analyze(BlockState state) {
        BakedQuad[] quads = registry.quadsFor(state);
        if (quads.length == 0) {
            return GRAY;
        }
        BakedQuad top = pickTop(quads);
        float[] uv = top.uvs();
        float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE;
        float v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            u0 = Math.min(u0, uv[i * 2]);
            u1 = Math.max(u1, uv[i * 2]);
            v0 = Math.min(v0, uv[i * 2 + 1]);
            v1 = Math.max(v1, uv[i * 2 + 1]);
        }
        int w = atlas.getWidth();
        int h = atlas.getHeight();
        int x0 = clamp((int) Math.floor(u0 * w), 0, w - 1);
        int x1 = clamp((int) Math.ceil(u1 * w) - 1, x0, w - 1);
        int y0 = clamp((int) Math.floor(v0 * h), 0, h - 1);
        int y1 = clamp((int) Math.ceil(v1 * h) - 1, y0, h - 1);
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int argb = atlas.getRGB(x, y);
                if ((argb >>> 24) < ALPHA_THRESHOLD) {
                    continue;
                }
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return null; // 顶面全透明
        }
        return new TopSample(r / (255f * n), g / (255f * n), b / (255f * n), top.tint());
    }

    /** 选取顶面面片：cullface=up 优先，其次法线朝上，最后退化为第一个面片。 */
    private static BakedQuad pickTop(BakedQuad[] quads) {
        for (BakedQuad q : quads) {
            if ("up".equals(q.cullface())) {
                return q;
            }
        }
        for (BakedQuad q : quads) {
            if (HiresTileRenderer.dominantNormal(q.positions()) == FaceDirection.UP) {
                return q;
            }
        }
        return quads[0];
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static BufferedImage decode(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new UncheckedIOException("贴图集 PNG 解码失败", e);
        }
    }
}
