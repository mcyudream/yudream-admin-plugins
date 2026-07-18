package online.yudream.plugin.worldmap.infrastructure.resource;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * 生物群系染色：grass/foliage colormap 查表（近似实现）。
 *
 * <p>原版规则：x = (int)((1-t)*255)，y = (int)((1 - d*t)*255)，
 * 其中 t=温度、d=降水（均 clamp 到 [0,1]）。群系温湿度表按常见群系硬编码，
 * 未知群系回退平原（0.8/0.4）。</p>
 *
 * <p>暂不含原版硬编码叶色（桦树/云杉/红树）与沼泽双线性噪声色，见模块报告。</p>
 */
public final class BiomeColors {

    /** colormap 缺失时的回退色（近似平原）。 */
    private static final int[] DEFAULT_GRASS = {0x91, 0xBD, 0x59};
    private static final int[] DEFAULT_FOLIAGE = {0x77, 0xAB, 0x2F};

    /** 群系 → [温度, 降水]（vanilla 1.20/1.21 常见群系）。 */
    private static final Map<String, float[]> BIOMES = Map.<String, float[]>ofEntries(
            Map.entry("plains", new float[]{0.8f, 0.4f}),
            Map.entry("sunflower_plains", new float[]{0.8f, 0.4f}),
            Map.entry("forest", new float[]{0.7f, 0.8f}),
            Map.entry("flower_forest", new float[]{0.7f, 0.8f}),
            Map.entry("birch_forest", new float[]{0.6f, 0.6f}),
            Map.entry("old_growth_birch_forest", new float[]{0.6f, 0.6f}),
            Map.entry("dark_forest", new float[]{0.7f, 0.8f}),
            Map.entry("taiga", new float[]{0.25f, 0.8f}),
            Map.entry("old_growth_pine_taiga", new float[]{0.3f, 0.8f}),
            Map.entry("old_growth_spruce_taiga", new float[]{0.25f, 0.8f}),
            Map.entry("snowy_taiga", new float[]{-0.5f, 0.4f}),
            Map.entry("snowy_plains", new float[]{-0.5f, 0.5f}),
            Map.entry("ice_spikes", new float[]{0.0f, 0.5f}),
            Map.entry("desert", new float[]{2.0f, 0.0f}),
            Map.entry("savanna", new float[]{2.0f, 0.0f}),
            Map.entry("savanna_plateau", new float[]{2.0f, 0.0f}),
            Map.entry("windswept_savanna", new float[]{2.0f, 0.0f}),
            Map.entry("jungle", new float[]{0.95f, 0.9f}),
            Map.entry("bamboo_jungle", new float[]{0.95f, 0.9f}),
            Map.entry("sparse_jungle", new float[]{0.95f, 0.8f}),
            Map.entry("swamp", new float[]{0.8f, 0.9f}),
            Map.entry("mangrove_swamp", new float[]{0.8f, 0.9f}),
            Map.entry("badlands", new float[]{2.0f, 0.0f}),
            Map.entry("eroded_badlands", new float[]{2.0f, 0.0f}),
            Map.entry("wooded_badlands", new float[]{2.0f, 0.0f}),
            Map.entry("mushroom_fields", new float[]{0.9f, 1.0f}),
            Map.entry("ocean", new float[]{0.5f, 0.5f}),
            Map.entry("deep_ocean", new float[]{0.5f, 0.5f}),
            Map.entry("warm_ocean", new float[]{0.5f, 0.5f}),
            Map.entry("lukewarm_ocean", new float[]{0.5f, 0.5f}),
            Map.entry("cold_ocean", new float[]{0.5f, 0.5f}),
            Map.entry("frozen_ocean", new float[]{0.0f, 0.5f}),
            Map.entry("river", new float[]{0.5f, 0.5f}),
            Map.entry("frozen_river", new float[]{0.0f, 0.5f}),
            Map.entry("beach", new float[]{0.8f, 0.4f}),
            Map.entry("snowy_beach", new float[]{0.05f, 0.3f}),
            Map.entry("stony_shore", new float[]{0.2f, 0.3f}),
            Map.entry("windswept_hills", new float[]{0.2f, 0.3f}),
            Map.entry("windswept_gravelly_hills", new float[]{0.2f, 0.3f}),
            Map.entry("windswept_forest", new float[]{0.2f, 0.3f}),
            Map.entry("meadow", new float[]{0.5f, 0.8f}),
            Map.entry("cherry_grove", new float[]{0.5f, 0.8f}),
            Map.entry("grove", new float[]{-0.2f, 0.8f}),
            Map.entry("snowy_slopes", new float[]{-0.3f, 0.9f}),
            Map.entry("jagged_peaks", new float[]{-0.7f, 0.9f}),
            Map.entry("frozen_peaks", new float[]{-0.7f, 0.9f}),
            Map.entry("stony_peaks", new float[]{-0.7f, 0.9f}),
            Map.entry("dripstone_caves", new float[]{0.8f, 0.4f}),
            Map.entry("lush_caves", new float[]{0.5f, 0.5f}),
            Map.entry("deep_dark", new float[]{0.8f, 0.4f}),
            Map.entry("nether_wastes", new float[]{2.0f, 0.0f}),
            Map.entry("crimson_forest", new float[]{2.0f, 0.0f}),
            Map.entry("warped_forest", new float[]{2.0f, 0.0f}),
            Map.entry("soul_sand_valley", new float[]{2.0f, 0.0f}),
            Map.entry("basalt_deltas", new float[]{2.0f, 0.0f}),
            Map.entry("the_end", new float[]{0.5f, 0.5f}),
            Map.entry("end_highlands", new float[]{0.5f, 0.5f}),
            Map.entry("end_midlands", new float[]{0.5f, 0.5f}),
            Map.entry("end_barrens", new float[]{0.5f, 0.5f}),
            Map.entry("small_end_islands", new float[]{0.5f, 0.5f}),
            Map.entry("the_void", new float[]{0.5f, 0.5f})
    );

    private static final float[] PLAINS = {0.8f, 0.4f};

    private final BufferedImage grassMap;
    private final BufferedImage foliageMap;

    public BiomeColors(BufferedImage grassMap, BufferedImage foliageMap) {
        this.grassMap = grassMap;
        this.foliageMap = foliageMap;
    }

    /** 草地染色 RGB（如 plains 约 0x91BD59）。biome 可带 minecraft: 前缀。 */
    public int[] grassColor(String biome) {
        return sample(grassMap, biome, DEFAULT_GRASS);
    }

    /** 树叶染色 RGB。 */
    public int[] foliageColor(String biome) {
        return sample(foliageMap, biome, DEFAULT_FOLIAGE);
    }

    private int[] sample(BufferedImage map, String biome, int[] fallback) {
        if (map == null) {
            return fallback.clone();
        }
        float[] td = BIOMES.getOrDefault(normalize(biome), PLAINS);
        double t = clamp(td[0]);
        double d = clamp(td[1]) * t; // 原版：降水先乘温度
        int x = (int) ((1 - t) * 255);
        int y = (int) ((1 - d) * 255);
        x = Math.min(x, map.getWidth() - 1);
        y = Math.min(y, map.getHeight() - 1);
        int rgb = map.getRGB(x, y);
        return new int[]{(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String normalize(String biome) {
        if (biome == null) {
            return "plains";
        }
        int idx = biome.indexOf(':');
        return idx >= 0 ? biome.substring(idx + 1) : biome;
    }
}
