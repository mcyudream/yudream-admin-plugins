package online.yudream.plugin.voxelithmap.infrastructure.engine;

/**
 * 渲染资源预算：按窗口规模把「堆」与「超时」一起放大。
 *
 * <p>为什么需要：大型地图渲染超时不是「跑挂了」，而是<b>固定上限套不住真实耗时</b>。实测烘焙约
 * 0.017–0.034 秒/区块（2771 区块 88 秒、54528 区块 909 秒），再叠加多遍渲染、模型采集、瓦片与 LOD，
 * 十几万区块的窗口撞穿默认的 180 分钟完全正常。堆早已按区块数预配（每区块 0.35MiB），
 * 超时却一直用固定值——这里把同一套思路补上：<b>配置值是下限，窗口越大上限越宽</b>，并留足余量。</p>
 *
 * <p>上限只放宽、不缩小：显式配置了更长超时的管理员不会被程序改短（与堆预配同一约定）。</p>
 */
public final class RenderBudget {

    /** 每区块预留的超时预算（秒/区块）：约为实测烘焙耗时的 20 倍，覆盖慢盘/慢 CPU、多遍与采集阶段。 */
    private static final double SECONDS_PER_CHUNK = 0.6;

    /** 自适应上限（分钟）：再怎么放大也要有个头，避免挂死的渲染无人回收。 */
    public static final int MAX_ADAPTIVE_MINUTES = 24 * 60;

    private RenderBudget() {
    }

    /**
     * 本次渲染的超时上限（分钟）。
     *
     * @param configuredMinutes 宿主配置的单次渲染超时（作为<b>下限</b>）
     * @param chunks            窗口内区块总数估算；{@code <= 0} 表示未知（未框选/整世界）
     * @return 配置值与按规模推算值的较大者，且不超过 {@link #MAX_ADAPTIVE_MINUTES}
     */
    public static int timeoutMinutes(int configuredMinutes, long chunks) {
        int floor = configuredMinutes <= 0 ? 180 : configuredMinutes;
        if (chunks <= 0) {
            // 整世界渲染估不出规模：只尊重配置，不凭空放大（日志里会提示可调高该设置）
            return floor;
        }
        long scaled = (long) Math.ceil(chunks * SECONDS_PER_CHUNK / 60.0);
        long minutes = Math.max(floor, scaled);
        return (int) Math.min(MAX_ADAPTIVE_MINUTES, minutes);
    }
}
