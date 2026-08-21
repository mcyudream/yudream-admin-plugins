package online.yudream.base.plugin.mcguess.domain;

import java.util.List;

/**
 * 游戏模式：猜物 / 猜生物 / 猜合成 / 迷雾 / 快答 / 宾果 / 找茬。每种模式每个群各一局进行中。
 * （比大小为个人连击玩法，不产生群内对局文档，不在此列。）
 */
public final class McguessMode {

    public static final String ITEM = "item";
    public static final String MOB = "mob";
    public static final String RECIPE = "recipe";
    public static final String FOG = "fog";
    public static final String QUIZ = "quiz";
    public static final String BINGO = "bingo";
    public static final String SPOT = "spot";

    public static final List<String> ALL = List.of(ITEM, MOB, RECIPE, FOG, QUIZ, BINGO, SPOT);

    private McguessMode() {
    }

    public static boolean isValid(String mode) {
        return ALL.contains(mode);
    }

    public static String zh(String mode) {
        return switch (mode == null ? "" : mode) {
            case ITEM -> "猜物";
            case MOB -> "猜生物";
            case RECIPE -> "猜合成";
            case FOG -> "迷雾";
            case QUIZ -> "快答";
            case BINGO -> "宾果";
            case SPOT -> "找茬";
            default -> "未知";
        };
    }
}
