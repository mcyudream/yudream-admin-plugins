package online.yudream.base.plugin.pony.application.dto;

public record PonyPlayerView(String userId, String qq, String nickname,
                             int played, int wins, int horsesPlaced,
                             int currentStreak, int bestStreak, long updatedAt) {
}
