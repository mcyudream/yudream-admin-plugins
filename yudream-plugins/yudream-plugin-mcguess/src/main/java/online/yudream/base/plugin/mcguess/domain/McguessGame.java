package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一局群内共享的猜物对局：随机出题，每个群（channelKey）同一时间最多一局进行中。
 */
public class McguessGame {

    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";

    /** 连续空猜测达到该次数后获得一次提示机会。 */
    public static final int HINT_EMPTY_STREAK = 6;

    private final String id;
    private final String connectionId;
    private final String platform;
    private final String channelId;
    private final String targetId;
    private final String startedByQq;
    private final String startedByUserId;
    private final long startedAt;
    private String status = STATUS_PLAYING;
    private final List<McGuess> guesses = new ArrayList<>();
    private final Set<String> revealed = new LinkedHashSet<>();
    private int emptyStreak;
    private int hintsUsed;
    private String winnerQq;
    private String winnerUserId;
    private Long endedAt;

    public McguessGame(String id, String connectionId, String platform, String channelId, String targetId,
                       String startedByQq, String startedByUserId, long startedAt) {
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
        return guesses.stream().anyMatch(guess -> itemId.equals(guess.matchedId()));
    }

    public void addGuess(McGuess guess) {
        guesses.add(guess);
    }

    public void reveal(String itemId) {
        revealed.add(itemId);
    }

    public void resetEmptyStreak() {
        this.emptyStreak = 0;
    }

    public void increaseEmptyStreak() {
        this.emptyStreak++;
    }

    public boolean hintAvailable() {
        return emptyStreak >= HINT_EMPTY_STREAK;
    }

    public void useHint() {
        this.hintsUsed++;
        this.emptyStreak = 0;
    }

    /** 仅供仓储从持久化文档恢复计数。 */
    public void restoreCounters(int emptyStreak, int hintsUsed) {
        this.emptyStreak = emptyStreak;
        this.hintsUsed = hintsUsed;
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

    public String getId() {
        return id;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getChannelKey() {
        return channelKey(connectionId, channelId);
    }

    public String getTargetId() {
        return targetId;
    }

    public String getStatus() {
        return status;
    }

    public List<McGuess> getGuesses() {
        return guesses;
    }

    public Set<String> getRevealed() {
        return revealed;
    }

    public int getEmptyStreak() {
        return emptyStreak;
    }

    public int getHintsUsed() {
        return hintsUsed;
    }

    public String getStartedByQq() {
        return startedByQq;
    }

    public String getStartedByUserId() {
        return startedByUserId;
    }

    public String getWinnerQq() {
        return winnerQq;
    }

    public String getWinnerUserId() {
        return winnerUserId;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public Long getEndedAt() {
        return endedAt;
    }

    /**
     * 一次猜测记录。
     *
     * @param result      WIN 猜中目标 / FOUND 命中合成树 / MISS 空猜
     * @param distance    到答案的合成次数，不可达为 null
     * @param occurrences 在合成树中的出现次数
     */
    public record McGuess(String input, String matchedId, String matchedZh, String result,
                          Integer distance, int occurrences, String qq, String userId, long at) {

        public static final String RESULT_WIN = "WIN";
        public static final String RESULT_FOUND = "FOUND";
        public static final String RESULT_MISS = "MISS";
    }
}
