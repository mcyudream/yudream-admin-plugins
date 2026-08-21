package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一局群内共享的迷雾猜物对局：目标物品的图标被迷雾（剪影 / 高斯模糊）遮蔽，
 * 每次猜错迷雾散去一分（阶段 0-5，5 完全清晰），直接猜中目标即获胜。
 */
public class FogGame implements ChannelGame {

    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";

    /** 迷雾阶段上限：0 纯剪影 → MAX_STAGE 完全清晰。 */
    public static final int MAX_STAGE = 5;

    private final String id;
    private final String connectionId;
    private final String platform;
    private final String channelId;
    private final String targetId;
    private final String startedByQq;
    private final String startedByUserId;
    private final long startedAt;
    private int stage;
    private final List<FogGuess> guesses = new ArrayList<>();
    private String status = STATUS_PLAYING;
    private String winnerQq;
    private String winnerUserId;
    private Long endedAt;

    public FogGame(String id, String connectionId, String platform, String channelId,
                   String targetId, String startedByQq, String startedByUserId, long startedAt) {
        this.id = id;
        this.connectionId = connectionId;
        this.platform = platform;
        this.channelId = channelId;
        this.targetId = targetId;
        this.startedByQq = startedByQq;
        this.startedByUserId = startedByUserId;
        this.startedAt = startedAt;
    }

    public static String channelKey(String connectionId, String channelId) {
        return connectionId + ":" + channelId;
    }

    public boolean isPlaying() {
        return STATUS_PLAYING.equals(status);
    }

    public boolean hasGuessed(String itemId) {
        return guesses.stream().anyMatch(guess -> itemId != null && itemId.equals(guess.matchedId()));
    }

    /** 猜错一次：迷雾散去一分（不超过上限）。 */
    public void stageUp() {
        this.stage = Math.min(this.stage + 1, MAX_STAGE);
    }

    public void addGuess(FogGuess guess) {
        guesses.add(guess);
    }

    public void win(String winnerQq, String winnerUserId, long endedAt) {
        this.status = STATUS_WON;
        this.winnerQq = winnerQq;
        this.winnerUserId = winnerUserId;
        this.endedAt = endedAt;
    }

    public void lose(long endedAt) {
        this.status = STATUS_LOST;
        this.endedAt = endedAt;
    }

    /** 仅供仓储从持久化文档恢复迷雾阶段。 */
    public void restoreStage(int stage) {
        this.stage = Math.max(0, Math.min(stage, MAX_STAGE));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getConnectionId() {
        return connectionId;
    }

    @Override
    public String getPlatform() {
        return platform;
    }

    @Override
    public String getChannelId() {
        return channelId;
    }

    @Override
    public String getChannelKey() {
        return channelKey(connectionId, channelId);
    }

    public String getTargetId() {
        return targetId;
    }

    public int getStage() {
        return stage;
    }

    public List<FogGuess> getGuesses() {
        return guesses;
    }

    public String getStartedByQq() {
        return startedByQq;
    }

    public String getStartedByUserId() {
        return startedByUserId;
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public String getWinnerQq() {
        return winnerQq;
    }

    @Override
    public String getWinnerUserId() {
        return winnerUserId;
    }

    @Override
    public long getStartedAt() {
        return startedAt;
    }

    @Override
    public Long getEndedAt() {
        return endedAt;
    }

    /**
     * 一次猜测记录。
     *
     * @param result WIN 猜中目标 / MISS 猜错（迷雾散去一分）
     */
    public record FogGuess(String input, String matchedId, String matchedZh, String result,
                           String qq, String userId, long at) {

        public static final String RESULT_WIN = "WIN";
        public static final String RESULT_MISS = "MISS";
    }
}
