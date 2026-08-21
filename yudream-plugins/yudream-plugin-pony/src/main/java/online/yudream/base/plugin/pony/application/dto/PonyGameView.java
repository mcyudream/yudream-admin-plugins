package online.yudream.base.plugin.pony.application.dto;

public record PonyGameView(String id, String channelId, int size, String status, int horsesPlaced,
                           int lives, int mistakes, String startedByQq, String winnerQq,
                           long startedAt, Long endedAt) {
}
