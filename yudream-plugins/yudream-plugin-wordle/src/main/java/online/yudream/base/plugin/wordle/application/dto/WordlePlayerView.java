package online.yudream.base.plugin.wordle.application.dto;

import java.util.Map;

public record WordlePlayerView(String userId, String qq, String nickname,
                               int englishPlayed, int englishWins, int idiomPlayed, int idiomWins,
                               int currentStreak, int bestStreak, Map<String, Integer> winDistribution,
                               long updatedAt) {
}
