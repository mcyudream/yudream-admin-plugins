package online.yudream.base.plugin.mcguess.application.dto;

/**
 * 管理端对局记录视图（七种模式统一；比大小为个人玩法，不产生对局记录）。
 *
 * @param mode   item / mob / recipe / fog / quiz / bingo / spot
 * @param target 猜物 / 猜合成 / 迷雾 / 找茬为目标中文名；猜生物为已填格进度（如 5/9）；
 *               快答为已答出题数进度（如 3/5）；宾果为已点亮格数进度（如 12/25）
 */
public record McguessGameView(String id, String mode, String modeZh, String connectionId, String platform,
                              String channelId, String target, String status, int guessCount,
                              String winnerQq, long startedAt, Long endedAt) {
}
