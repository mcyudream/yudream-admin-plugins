package online.yudream.plugin.voxelithmap.application.dto;

/**
 * 框选底图的状态与地理信息。
 *
 * <p>前端据此把「画布像素」换算成方块范围；{@code suggestedMinY} 是必须回填的
 * ——存档高度差异极大，写死默认值会把整片几何裁掉。</p>
 *
 * @param ready           底图是否可用
 * @param state           边车侧预览状态
 * @param percent         生成进度（-1 = 无法计算）
 * @param done            已解析 region 数
 * @param total           region 总数
 * @param message         状态文本
 * @param uploadId        边车侧存档 id
 * @param dimension       维度
 * @param error           失败原因（边车不可用/存档缺失等）
 * @param meta            地理信息；未就绪为 null
 * @param suggestedMinY   建议的最低渲染高度；无地表数据为 null
 * @param imageUrl        底图地址（相对插件 API 前缀）
 * @param windowSize      推荐窗口图边长（像素）
 */
public record PreviewDTO(
        boolean ready,
        String state,
        int percent,
        int done,
        int total,
        String message,
        String uploadId,
        String dimension,
        String error,
        Meta meta,
        Integer suggestedMinY,
        String imageUrl,
        int windowSize) {

    /**
     * 地理信息（字段与 Voxelith {@code PreviewMeta} 对齐）。
     *
     * @param version     缓存 schema 版本
     * @param originX     左上角像素对应方块 X
     * @param originZ     左上角像素对应方块 Z
     * @param step        每像素方块数
     * @param width       像素宽
     * @param depth       像素深
     * @param blockMinX   有内容区块包围盒（含端点）
     * @param blockMinZ   同上
     * @param blockMaxX   同上
     * @param blockMaxZ   同上
     * @param regionCount region 数
     * @param chunkCount  区块数
     * @param minSurfaceY 地表最低高度
     * @param maxSurfaceY 地表最高高度
     */
    public record Meta(int version, int originX, int originZ, int step, int width, int depth, int blockMinX,
                       int blockMinZ, int blockMaxX, int blockMaxZ, long regionCount, long chunkCount,
                       int minSurfaceY, int maxSurfaceY) {
    }
}
