package online.yudream.plugin.worldmap.infrastructure.world;

import java.nio.file.Path;

/**
 * 世界存档加载入口：按维度定位 region 目录并创建 {@link WorldAccess}。
 * region 目录不存在时仍返回可用实例（所有坐标视为未生成）。
 */
public final class WorldLoader {

    private WorldLoader() {
    }

    /**
     * 加载世界。
     *
     * @param worldDir  存档根目录（含 level.dat 的那一级）
     * @param dimension overworld / nether / the_end（允许 minecraft: 前缀）
     * @return 世界只读访问入口，使用完毕须 {@link WorldAccess#close()}
     */
    public static WorldAccess load(Path worldDir, String dimension) {
        Path regionDir = switch (normalize(dimension)) {
            case "overworld" -> worldDir.resolve("region");
            case "nether" -> worldDir.resolve("DIM-1").resolve("region");
            case "the_end" -> worldDir.resolve("DIM1").resolve("region");
            default -> throw new IllegalArgumentException("不支持的维度: " + dimension);
        };
        return AnvilWorldAccess.open(regionDir);
    }

    private static String normalize(String dimension) {
        if (dimension == null) {
            throw new IllegalArgumentException("维度不能为 null");
        }
        String d = dimension.toLowerCase();
        if (d.startsWith("minecraft:")) {
            d = d.substring("minecraft:".length());
        }
        return d;
    }
}
