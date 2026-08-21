package online.yudream.base.plugin.wordle.domain;

import java.util.List;

/**
 * 一次猜测记录。userId 为绑定系统用户 ID 的字符串形式，未绑定玩家为 null。
 */
public record Guess(String word, List<LetterState> states, String userId, String qq, long at) {

    public String tiles() {
        return WordleEvaluator.tiles(states);
    }
}
