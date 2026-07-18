package online.yudream.plugin.worldmap.infrastructure.render;

import online.yudream.plugin.worldmap.infrastructure.resource.BiomeColors;
import online.yudream.plugin.worldmap.infrastructure.resource.BlockModelRegistry;
import online.yudream.plugin.worldmap.infrastructure.world.BlockState;
import online.yudream.plugin.worldmap.infrastructure.world.WorldTileManifest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * lowres tile 渲染器（CONTRACT §5）：512×512 俯视正交 PNG 金字塔。
 *
 * <ul>
 *   <li>lod0：1 方块/px，取每柱最高方块顶面平均色 × 染色 × 高度明暗系数，
 *       空气柱/全透明顶面 → 透明像素。</li>
 *   <li>lodN（N≥1）：由 lod(N-1) 的 2×2 区域平均下采样（全透明子区域跳过）。</li>
 *   <li>只产出与 hires 范围重叠的 tile；全透明 tile 不产出。</li>
 * </ul>
 */
final class LowresRenderer {

    /** lowres tile 像素边长，契约固定 512。 */
    static final int TILE_PX = 512;
    /** 最高 lod（含），对齐 CONTRACT §3 lowresMaxLod。 */
    static final int MAX_LOD = 4;

    private final RenderWorldView world;
    private final BiomeColors biomeColors;
    private final TopColorSampler topColors;

    LowresRenderer(RenderWorldView world, BlockModelRegistry registry, BiomeColors biomeColors) {
        this.world = world;
        this.biomeColors = biomeColors;
        this.topColors = new TopColorSampler(registry);
    }

    /**
     * 生成覆盖 [minBX, maxBX) × [minBZ, maxBZ)（方块坐标）的全部 lowres tile。
     *
     * @return 实际产出的 tile 数（各 lod 合计，全透明不计）
     */
    int render(WorldTileManifest manifest, TileSink sink, ProgressListener progress, int progressTotal) throws IOException {
        Map<Long, BufferedImage> prevLevel = Map.of();
        Set<Long> requested = new HashSet<>();
        for (WorldTileManifest.Tile tile : manifest.tiles()) {
            requested.add(tileKey(Math.floorDiv(tile.x(), 16), Math.floorDiv(tile.z(), 16)));
        }
        int produced = 0;
        for (int lod = 0; lod <= MAX_LOD; lod++) {
            Map<Long, BufferedImage> current = new HashMap<>();
            for (long key : requested.stream().sorted().toList()) {
                int tx = tileX(key);
                int tz = tileZ(key);
                BufferedImage img = lod == 0 ? renderLod0(tx, tz) : downsample(tx, tz, prevLevel);
                if (img == null) {
                    continue;
                }
                current.put(key, img);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 15);
                ImageIO.write(img, "png", bos);
                sink.putLowresTile(lod, tx, tz, bos.toByteArray());
                produced++;
            }
            if (progress != null) {
                progress.progress(progressTotal, progressTotal,
                        "低清 tile lod" + lod + " 完成（" + current.size() + " 个）");
            }
            if (current.isEmpty()) {
                break;
            }
            prevLevel = current;
            requested = new HashSet<>();
            for (long key : current.keySet()) {
                requested.add(tileKey(Math.floorDiv(tileX(key), 2), Math.floorDiv(tileZ(key), 2)));
            }
        }
        return produced;
    }

    // ---------- lod0 ----------

    private BufferedImage renderLod0(int tx, int tz) {
        BufferedImage img = new BufferedImage(TILE_PX, TILE_PX, BufferedImage.TYPE_INT_ARGB);
        boolean anyOpaque = false;
        int bx0 = tx * TILE_PX;
        int bz0 = tz * TILE_PX;
        for (int pz = 0; pz < TILE_PX; pz++) {
            for (int px = 0; px < TILE_PX; px++) {
                int x = bx0 + px;
                int z = bz0 + pz;
                int y = world.maxY(x, z);
                BlockState state = world.blockState(x, y, z);
                if (state.isAir()) {
                    continue; // 空气柱 → 透明
                }
                TopColorSampler.TopSample sample = topColors.of(state);
                if (sample == null) {
                    continue; // 顶面全透明（玻璃等）
                }
                float r = sample.r(), g = sample.g(), b = sample.b();
                float[] tint = Tints.of(sample.tint(), biomeColors, world.biome(x, y, z), state.name());
                if (tint != null) {
                    r *= tint[0];
                    g *= tint[1];
                    b *= tint[2];
                }
                // 高度明暗：越高越亮（参考原版地图物品配色）
                float hf = heightFactor(y);
                img.setRGB(px, pz, 0xFF000000
                        | (to8(r * hf) << 16) | (to8(g * hf) << 8) | to8(b * hf));
                anyOpaque = true;
            }
        }
        return anyOpaque ? img : null;
    }

    /** 高度明暗系数：clamp(0.75 + (y-64)/256, 0.6, 1.15)。 */
    private static float heightFactor(int y) {
        return Math.max(0.6f, Math.min(1.15f, 0.75f + (y - 64) / 256f));
    }

    private static int to8(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }

    // ---------- lodN 下采样 ----------

    /** 由上一层 2×2 邻接 tile 各取 256×256 象限拼成，逐像素 2×2 平均。 */
    private BufferedImage downsample(int tx, int tz, Map<Long, BufferedImage> prevLevel) {
        BufferedImage img = new BufferedImage(TILE_PX, TILE_PX, BufferedImage.TYPE_INT_ARGB);
        boolean anyOpaque = false;
        for (int qz = 0; qz < 2; qz++) {
            for (int qx = 0; qx < 2; qx++) {
                BufferedImage src = prevLevel.get(tileKey(tx * 2 + qx, tz * 2 + qz));
                if (src == null) {
                    continue; // 上一层缺失 = 全透明
                }
                for (int py = 0; py < TILE_PX / 2; py++) {
                    for (int px = 0; px < TILE_PX / 2; px++) {
                        int argb = average2x2(src, px * 2, py * 2);
                        if (argb != 0) {
                            img.setRGB(qx * TILE_PX / 2 + px, qz * TILE_PX / 2 + py, argb);
                            anyOpaque = true;
                        }
                    }
                }
            }
        }
        return anyOpaque ? img : null;
    }

    /** 2×2 平均：颜色按非透明像素平均，alpha 按 4 像素整体平均（半透明边缘）。 */
    private static int average2x2(BufferedImage src, int x, int y) {
        int r = 0, g = 0, b = 0, a = 0, n = 0;
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int argb = src.getRGB(x + dx, y + dy);
                int alpha = argb >>> 24;
                a += alpha;
                if (alpha > 0) {
                    r += (argb >> 16) & 0xFF;
                    g += (argb >> 8) & 0xFF;
                    b += argb & 0xFF;
                    n++;
                }
            }
        }
        if (n == 0) {
            return 0;
        }
        return ((a / 4) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    private static long tileKey(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
    }

    private static int tileX(long key) {
        return (int) (key >> 32);
    }

    private static int tileZ(long key) {
        return (int) key;
    }
}
