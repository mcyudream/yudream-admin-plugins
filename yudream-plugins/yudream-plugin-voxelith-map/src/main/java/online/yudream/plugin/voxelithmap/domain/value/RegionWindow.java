package online.yudream.plugin.voxelithmap.domain.value;

/**
 * 方块级渲染范围窗口（含端点）。
 *
 * <p>与 Voxelith {@code RenderMapCli} 的 {@code --min-x/--max-x/--min-z/--max-z} 一一对应。
 * 为什么不直接用 region 窗口：region 是按 512 方块取整的，按方块范围裁剪才能刚好只渲染
 * 框选区域（例如一个学校）。</p>
 *
 * @param minX 最小 X（含）
 * @param minZ 最小 Z（含）
 * @param maxX 最大 X（含）
 * @param maxZ 最大 Z（含）
 */
public record RegionWindow(int minX, int minZ, int maxX, int maxZ) {

    public RegionWindow {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("渲染范围非法：最小坐标不能大于最大坐标");
        }
    }

    /** 覆盖的区块数（按 16 方块/区块向上取整，仅供估算与提示）。 */
    public long chunkCount() {
        long chunksX = (long) Math.ceil((maxX - minX + 1) / 16.0);
        long chunksZ = (long) Math.ceil((maxZ - minZ + 1) / 16.0);
        return chunksX * chunksZ;
    }

    /** 宽度（方块，含端点）。 */
    public int widthBlocks() {
        return maxX - minX + 1;
    }

    /** 深度（方块，含端点）。 */
    public int depthBlocks() {
        return maxZ - minZ + 1;
    }

    /** 与另一窗口是否相交（用于增量重渲染判断范围重叠）。 */
    public boolean intersects(RegionWindow other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public String describe() {
        return "x[" + minX + "," + maxX + "] z[" + minZ + "," + maxZ + "]";
    }
}
