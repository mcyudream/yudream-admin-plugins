package online.yudream.base.plugin.playtimepoints.application.dto;

/** 一轮扫描的结果。sessions 为本次落库的结算笔数，credited 为实际向钱包入账的笔数。 */
public record ScanResult(int credited, int sessions, String message) {
}
