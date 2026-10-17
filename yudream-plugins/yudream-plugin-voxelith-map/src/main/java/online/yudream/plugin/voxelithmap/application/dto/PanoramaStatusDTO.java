package online.yudream.plugin.voxelithmap.application.dto;

/**
 * 全景图烘焙状态。
 *
 * <p>全景图是「服务端把整张地图烘成一张透视 PNG」的产物：客户端不必同时画出几千万三角形。
 * 它读的是渲染留下的中间产物（地表栅格 + 高度场），所以<b>必须先渲染过一次</b>。</p>
 *
 * @param mapId      地图 id
 * @param state      {@code IDLE} / {@code RUNNING} / {@code SUCCEEDED} / {@code FAILED}
 * @param percent    进度百分比（全景 CLI 不打进度，按阶段给 10/60/90/100）
 * @param message    当前阶段文本
 * @param generated  当前已发布代里是否已有全景图（据此决定前端是否显示它）
 * @param width      输出像素宽
 * @param height     输出像素高
 * @param yaw        水平朝向（度，0 = 朝北）
 * @param pitch      俯角（度）
 * @param fov        垂直视场角（度）
 * @param distance   相机距离系数（× 地图对角线）
 * @param startedAt  本次开始时间（ISO-8601）
 * @param finishedAt 本次结束时间（ISO-8601）；运行中为 {@code null}
 * @param error      失败原因；成功或未运行为 {@code null}
 * @param logTail    子进程输出尾巴（排障用，最多 2000 字符）
 */
public record PanoramaStatusDTO(
        String mapId,
        String state,
        int percent,
        String message,
        boolean generated,
        int width,
        int height,
        double yaw,
        double pitch,
        double fov,
        double distance,
        String startedAt,
        String finishedAt,
        String error,
        String logTail) {

    /** 未曾触发过、也还没有产物。 */
    public static PanoramaStatusDTO idle(String mapId, boolean generated, int width, int height,
                                         double yaw, double pitch, double fov, double distance) {
        return new PanoramaStatusDTO(mapId, "IDLE", 0,
                generated ? "已有全景图，可重新烘焙" : "尚未烘焙全景图", generated,
                width, height, yaw, pitch, fov, distance, null, null, null, "");
    }
}
