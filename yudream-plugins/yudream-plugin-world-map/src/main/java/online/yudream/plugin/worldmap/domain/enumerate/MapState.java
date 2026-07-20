package online.yudream.plugin.worldmap.domain.enumerate;

/**
 * 地图实例状态。
 */
public enum MapState {
    /** 已创建，尚未渲染 */
    EMPTY,
    /** 渲染中 */
    RENDERING,
    /** 渲染完成，可浏览 */
    READY,
    /** 渲染已取消 */
    CANCELLED,
    /** 上次渲染失败 */
    FAILED
}
