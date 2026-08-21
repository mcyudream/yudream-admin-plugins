package online.yudream.base.plugin.mcguess.application.dto;

/**
 * 管理端概览：七种模式的对局统计 + 数据规模（比大小为个人玩法，不产生群对局）。
 *
 * @param item             猜物（随机目标物品）
 * @param mob              猜生物（条件填格子）
 * @param recipe           猜合成（反向配方）
 * @param fog              迷雾（图标渐显）
 * @param quiz             快答（合成计数抢答）
 * @param bingo            宾果（5x5 连线）
 * @param spot             找茬（配方找错格）
 * @param playerCount      有战绩的玩家数
 * @param itemCount        数据集物品总数
 * @param craftableCount   可合成物品数
 * @param guessTargetCount 随机出题候选池大小
 * @param mobCount         生物数据集生物数
 * @param conditionCount   生物条件数
 */
public record McguessOverview(ModeStats item, ModeStats mob, ModeStats recipe,
                              ModeStats fog, ModeStats quiz, ModeStats bingo, ModeStats spot,
                              long playerCount,
                              int itemCount, int craftableCount, int guessTargetCount,
                              int mobCount, int conditionCount) {

    public record ModeStats(long total, long playing, long won) {
    }
}
