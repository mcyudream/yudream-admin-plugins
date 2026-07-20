package online.yudream.plugin.worldmap.infrastructure.render;

import online.yudream.plugin.worldmap.infrastructure.resource.BakedQuad;
import online.yudream.plugin.worldmap.infrastructure.resource.BiomeColors;
import online.yudream.plugin.worldmap.infrastructure.resource.BlockModelRegistry;
import online.yudream.plugin.worldmap.infrastructure.resource.TintType;
import online.yudream.plugin.worldmap.infrastructure.world.BlockState;

/**
 * hires tile 渲染器（CONTRACT §4）：32×32 方块范围全高度几何生成。
 *
 * <ul>
 *   <li>面剔除：面片 cullface 指向的相邻方块在该面全封闭（见 {@link StateOcclusion}）则跳过；
 *       无 cullface 的面片（植物 cross 等）不剔除；相邻为空气/未生成不剔除。</li>
 *   <li>AO（逐顶点）：面片法线偏移一格的 2×2 邻域角点（side1/side2/corner）
 *       统计遮挡数（非空气且非半透明），0..3 → [1.0, 0.85, 0.7, 0.55]；
 *       shade=false 恒为 1。</li>
 *   <li>光照（逐顶点）：取 AO 同组 4 格的 blocklight/skylight 最大值（平滑近似）。</li>
 *   <li>顶点色：GRASS/FOLIAGE/WATER 经群系色表/水色染色，其余 1,1,1。</li>
 * </ul>
 */
final class HiresTileRenderer {

    /** hires tile 边长（方块数），契约固定 32。 */
    static final int TILE_SIZE = 32;

    /** AO 遮挡数 → 系数。 */
    private static final float[] AO_TABLE = {1.0f, 0.85f, 0.7f, 0.55f};
    /** 轴向法线判定阈值：叉积主轴分量占比。 */
    private static final float NORMAL_AXIS_THRESHOLD = 0.9f;

    private final RenderWorldView world;
    private final BlockModelRegistry registry;
    private final BiomeColors biomeColors;
    private final StateOcclusion occlusion;

    HiresTileRenderer(RenderWorldView world, BlockModelRegistry registry,
                      BiomeColors biomeColors, StateOcclusion occlusion) {
        this.world = world;
        this.registry = registry;
        this.biomeColors = biomeColors;
        this.occlusion = occlusion;
    }

    /** 渲染一个 tile；无任何面片时返回 null（空 tile 不产出）。 */
    byte[] renderTile(int tx, int tz) {
        HiresTileBuilder out = new HiresTileBuilder();
        int x0 = tx * TILE_SIZE;
        int z0 = tz * TILE_SIZE;
        for (int x = x0; x < x0 + TILE_SIZE; x++) {
            for (int z = z0; z < z0 + TILE_SIZE; z++) {
                int top = world.maxY(x, z);
                int bottom = world.minY();
                for (int y = bottom; y <= top; y++) {
                    BlockState state = world.blockState(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    BakedQuad[] quads = registry.quadsFor(state);
                    if (quads.length > 0) {
                        emitBlock(x, y, z, state, quads, out);
                    }
                }
            }
        }
        return out.isEmpty() ? null : out.toGzipJson(tx, tz);
    }

    private void emitBlock(int x, int y, int z, BlockState state, BakedQuad[] quads, HiresTileBuilder out) {
        String biome = null; // 按需取群系
        for (BakedQuad quad : quads) {
            FaceDirection cull = FaceDirection.byName(quad.cullface());
            if (cull != null) {
                BlockState neighbor = world.blockState(x + cull.dx, y + cull.dy, z + cull.dz);
                if (occlusion.of(neighbor).seals(cull.opposite())) {
                    continue; // 相邻方块该面全封闭 → 剔除
                }
            }
            // 顶点染色（整面同色）
            float r = 1f, g = 1f, b = 1f;
            if (quad.tint() != TintType.NONE && quad.tint() != TintType.REDSTONE) {
                if (biome == null) {
                    biome = world.biome(x, y, z);
                }
                float[] tint = Tints.of(quad.tint(), biomeColors, biome, state.name());
                if (tint != null) {
                    r = tint[0];
                    g = tint[1];
                    b = tint[2];
                }
            }
            emitQuad(x, y, z, quad, cull, r, g, b, out);
        }
    }

    private void emitQuad(int x, int y, int z, BakedQuad quad, FaceDirection cull,
                          float r, float g, float b, HiresTileBuilder out) {
        float[] lp = quad.positions();
        // 法线：优先 cullface，否则由顶点叉积取主轴（斜面可能无法判定 → null）
        FaceDirection normal = cull != null ? cull : dominantNormal(lp);

        float[] wp = new float[12];
        for (int i = 0; i < 4; i++) {
            wp[i * 3] = x + lp[i * 3] / 16f;
            wp[i * 3 + 1] = y + lp[i * 3 + 1] / 16f;
            wp[i * 3 + 2] = z + lp[i * 3 + 2] / 16f;
        }

        // 面方向漫反射明暗（原版 DiffuseLight：上 1.0、下 0.5、南北 0.8、东西 0.6）
        float dirShade = 1f;
        if (normal != null && quad.shade()) {
            dirShade = switch (normal) {
                case UP -> 1.0f;
                case DOWN -> 0.5f;
                case NORTH, SOUTH -> 0.8f;
                case EAST, WEST -> 0.6f;
            };
        }

        float[] ao = {1f, 1f, 1f, 1f};
        float[] bl = new float[4];
        float[] sl = new float[4];
        for (int i = 0; i < 4; i++) {
            if (normal == null) {
                // 无法确定朝向（斜面）：光照取本格，无 AO
                bl[i] = world.blockLight(x, y, z);
                sl[i] = world.skyLight(x, y, z);
                continue;
            }
            int axis = normal.axis;
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
            // 面邻接格 F，以及该顶点角点方向的 side1/side2/corner
            int[] f = {x + normal.dx, y + normal.dy, z + normal.dz};
            int ou = lp[i * 3 + u] >= 8f ? 1 : -1;
            int ov = lp[i * 3 + v] >= 8f ? 1 : -1;
            int[] s1 = {f[0], f[1], f[2]};
            s1[u] += ou;
            int[] s2 = {f[0], f[1], f[2]};
            s2[v] += ov;
            int[] cc = {f[0], f[1], f[2]};
            cc[u] += ou;
            cc[v] += ov;
            if (quad.shade()) {
                int occ = occludes(s1) + occludes(s2) + occludes(cc);
                ao[i] = AO_TABLE[occ] * dirShade;
            }
            // 原版平滑光照：4 格平均（而非取最大），过渡更平滑
            bl[i] = (world.blockLight(f[0], f[1], f[2]) + world.blockLight(s1[0], s1[1], s1[2])
                    + world.blockLight(s2[0], s2[1], s2[2]) + world.blockLight(cc[0], cc[1], cc[2])) / 4f;
            sl[i] = (world.skyLight(f[0], f[1], f[2]) + world.skyLight(s1[0], s1[1], s1[2])
                    + world.skyLight(s2[0], s2[1], s2[2]) + world.skyLight(cc[0], cc[1], cc[2])) / 4f;
        }
        out.addQuad(wp, quad.uvs(), r, g, b, ao, bl, sl, quad.translucent());
    }

    /** AO 遮挡判定：非空气且非半透明。 */
    private int occludes(int[] p) {
        BlockState s = world.blockState(p[0], p[1], p[2]);
        return !s.isAir() && !occlusion.of(s).translucent() ? 1 : 0;
    }

    /**
     * 由面片顶点叉积推导主轴法线（供无 cullface 的面片取光照方向）。
     * 斜面（旋转 45° 等元素）返回 null。
     */
    static FaceDirection dominantNormal(float[] p) {
        float ux = p[3] - p[0];
        float uy = p[4] - p[1];
        float uz = p[5] - p[2];
        float vx = p[6] - p[0];
        float vy = p[7] - p[1];
        float vz = p[8] - p[2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) {
            return null;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ax >= ay && ax >= az) {
            return ax >= NORMAL_AXIS_THRESHOLD ? (nx > 0 ? FaceDirection.EAST : FaceDirection.WEST) : null;
        }
        if (ay >= ax && ay >= az) {
            return ay >= NORMAL_AXIS_THRESHOLD ? (ny > 0 ? FaceDirection.UP : FaceDirection.DOWN) : null;
        }
        return az >= NORMAL_AXIS_THRESHOLD ? (nz > 0 ? FaceDirection.SOUTH : FaceDirection.NORTH) : null;
    }
}
