package online.yudream.plugin.worldmap.infrastructure.render;

import online.yudream.plugin.worldmap.infrastructure.world.WorldTileManifest;

import java.nio.file.Path;

/**
 * 渲染任务参数。
 *
 * @param worldDir           存档根目录（含 level.dat 的那一级）
 * @param clientJar          原版客户端 jar（渲染资产来源）
 * @param dimension          overworld / nether / the_end（允许 minecraft: 前缀）
 * @param minTileX           hires tile 范围下限（闭区间）
 * @param minTileZ           hires tile 范围下限（闭区间）
 * @param maxTileX           hires tile 范围上限（闭区间）
 * @param maxTileZ           hires tile 范围上限（闭区间）
 * @param stripNetherCeiling nether 维度下剥离 y≥120 的基岩顶层
 */
public record RenderJob(Path worldDir, Path clientJar, String dimension,
                        int minTileX, int minTileZ, int maxTileX, int maxTileZ,
                        boolean stripNetherCeiling, WorldTileManifest tileManifest) {

    public RenderJob(Path worldDir, Path clientJar, String dimension,
                     int minTileX, int minTileZ, int maxTileX, int maxTileZ,
                     boolean stripNetherCeiling) {
        this(worldDir, clientJar, dimension, minTileX, minTileZ, maxTileX, maxTileZ,
                stripNetherCeiling, WorldTileManifest.rectangular(minTileX, minTileZ, maxTileX, maxTileZ));
    }
}
