package online.yudream.plugin.voxelithmap.domain.enumerate;

/**
 * 地图项目状态。
 *
 * <p>{@code EMPTY} 表示尚未成功渲染过任何一代产物（公开查看器无内容可载）；
 * {@code READY} 表示当前发布代可用。渲染过程中状态切到 {@code RENDERING}，
 * 失败回到渲染前的状态（已有可见代时仍是 {@code READY}——失败不夺走线上内容）。</p>
 */
public enum ProjectState {
    /** 尚未有可用产物。 */
    EMPTY,
    /** 有任务在跑。 */
    RENDERING,
    /** 当前发布代可用。 */
    READY,
    /** 最近一次渲染失败且无历史产物。 */
    FAILED,
}
