package online.yudream.base.plugin.wordle.application.dto;

public record WordleGameView(String id, String channelId, String mode, String modeLabel, int length,
                             boolean hardMode, String status, int maxGuesses, int guessCount,
                             String answer, String startedByQq, String winnerQq, long startedAt, Long endedAt) {
}
