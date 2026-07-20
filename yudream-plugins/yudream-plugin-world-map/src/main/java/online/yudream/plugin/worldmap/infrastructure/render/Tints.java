package online.yudream.plugin.worldmap.infrastructure.render;

import online.yudream.plugin.worldmap.infrastructure.resource.BiomeColors;
import online.yudream.plugin.worldmap.infrastructure.resource.TintType;

import java.util.Map;

/** 染色工具：群系色（草/树叶）与水色，供 hires 顶点色与 lowres 像素色共用。 */
final class Tints {

    /** 原版默认水色 #3F76E4。 */
    static final float[] WATER = {0x3F / 255f, 0x76 / 255f, 0xE4 / 255f};

    /** 原版硬编码叶色（不随群系变化）。 */
    private static final Map<String, float[]> HARDCODED_LEAVES = Map.of(
            "minecraft:spruce_leaves", rgb255(0x61, 0x99, 0x61),
            "minecraft:birch_leaves", rgb255(0x80, 0xA7, 0x55),
            "minecraft:mangrove_leaves", rgb255(0x8D, 0xB1, 0x27)
    );

    /** 原版群系水色表（未命中用默认水色）。 */
    private static final Map<String, float[]> BIOME_WATER = Map.ofEntries(
            Map.entry("minecraft:swamp", rgb255(0x61, 0x7B, 0x64)),
            Map.entry("minecraft:mangrove_swamp", rgb255(0x61, 0x7B, 0x64)),
            Map.entry("minecraft:cold_ocean", rgb255(0x3D, 0x57, 0xD6)),
            Map.entry("minecraft:deep_cold_ocean", rgb255(0x3D, 0x57, 0xD6)),
            Map.entry("minecraft:frozen_ocean", rgb255(0x39, 0x38, 0xC9)),
            Map.entry("minecraft:deep_frozen_ocean", rgb255(0x39, 0x38, 0xC9)),
            Map.entry("minecraft:frozen_river", rgb255(0x39, 0x38, 0xC9)),
            Map.entry("minecraft:frozen_peaks", rgb255(0x39, 0x38, 0xC9)),
            Map.entry("minecraft:snowy_plains", rgb255(0x3D, 0x57, 0xD6)),
            Map.entry("minecraft:snowy_taiga", rgb255(0x3D, 0x57, 0xD6)),
            Map.entry("minecraft:ice_spikes", rgb255(0x3D, 0x57, 0xD6)),
            Map.entry("minecraft:warm_ocean", rgb255(0x43, 0xD5, 0xEE)),
            Map.entry("minecraft:deep_warm_ocean", rgb255(0x43, 0xD5, 0xEE)),
            Map.entry("minecraft:lukewarm_ocean", rgb255(0x45, 0xAD, 0xF2)),
            Map.entry("minecraft:deep_lukewarm_ocean", rgb255(0x45, 0xAD, 0xF2))
    );

    private Tints() {
    }

    /**
     * 按染色类型取色（float 0..1 RGB）。
     *
     * @param blockName 方块名（用于原版硬编码叶色特判）
     * @return 染色 RGB；NONE / REDSTONE 返回 null（表示不染色，按 1,1,1）
     */
    static float[] of(TintType type, BiomeColors colors, String biome, String blockName) {
        return switch (type) {
            case GRASS -> rgb255(colors.grassColor(biome));
            case FOLIAGE -> {
                float[] hardcoded = HARDCODED_LEAVES.get(blockName);
                yield hardcoded != null ? hardcoded.clone() : rgb255(colors.foliageColor(biome));
            }
            case WATER -> {
                float[] water = biome == null ? null : BIOME_WATER.get(biome);
                yield water != null ? water.clone() : WATER.clone();
            }
            default -> null;
        };
    }

    static float[] rgb255(int[] rgb) {
        return rgb255(rgb[0], rgb[1], rgb[2]);
    }

    static float[] rgb255(int r, int g, int b) {
        return new float[]{r / 255f, g / 255f, b / 255f};
    }
}
