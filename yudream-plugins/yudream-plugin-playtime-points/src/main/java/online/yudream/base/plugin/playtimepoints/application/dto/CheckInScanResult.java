package online.yudream.base.plugin.playtimepoints.application.dto;

/**
 * 一轮打卡积分拉取的结果。
 *
 * @param credited 本轮新发放的条数
 * @param scanned  本轮读取到的打卡条数（含已发放而被幂等跳过的边界记录）
 * @param retried  因失败而留待下一轮重试的条数（0 或 1：本轮在第一条失败处就停下，游标不越过它）
 * @param message  面向管理端的说明（正常情况下为 {@code OK}）
 */
public record CheckInScanResult(int credited, int scanned, int retried, String message) {

    public static CheckInScanResult skipped(String message) {
        return new CheckInScanResult(0, 0, 0, message);
    }
}
