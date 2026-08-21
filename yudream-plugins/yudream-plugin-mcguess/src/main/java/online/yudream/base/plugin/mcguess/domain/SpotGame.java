package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一局群内共享的配方找茬对局：展示一个真实 3x3 配方，但某一非空格被替换成了
 * 违和的物品（优先同族变体，如把红色羊毛换成蓝色羊毛），第一个指出错误格（1-9）的人获胜。
 */
public class SpotGame implements ChannelGame {

    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";

    private final String id;
    private final String connectionId;
    private final String platform;
    private final String channelId;
    /** 配方目标（产物）物品 id。 */
    private final String targetId;
    /** 展示用 3x3 网格（9 格，空位为 null；含被换掉的那一格）。 */
    private final List<String> grid;
    /** 错误格序号（1-9）。 */
    private final int wrongCell;
    /** 错误格原本应该是的物品 id。 */
    private final String correctId;
    private final String startedByQq;
    private final String startedByUserId;
    private final long startedAt;
    private final List<SpotGuess> guesses = new ArrayList<>();
    private String status = STATUS_PLAYING;
    private String winnerQq;
    private String winnerUserId;
    private Long endedAt;

    public SpotGame(String id, String connectionId, String platform, String channelId,
                    String targetId, List<String> grid, int wrongCell, String correctId,
                    String startedByQq, String startedByUserId, long startedAt) {
        this.id = id;
        this.connectionId = connectionId;
        this.platform = platform;
        this.channelId = channelId;
        this.targetId = targetId;
        this.grid = Collections.unmodifiableList(new ArrayList<>(grid));
        this.wrongCell = wrongCell;
        this.correctId = correctId;
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

    /** 格子序号是否合法（1-9）。 */
    public static boolean isValidCell(int cell) {
        return cell >= 1 && cell <= 9;
    }

    /** 该格是否是被动过手脚的错误格。 */
    public boolean isWrongCell(int cell) {
        return cell == wrongCell;
    }

    public void addGuess(SpotGuess guess) {
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

    public List<String> getGrid() {
        return grid;
    }

    public int getWrongCell() {
        return wrongCell;
    }

    public String getCorrectId() {
        return correctId;
    }

    public List<SpotGuess> getGuesses() {
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
     * 一次指认记录。
     *
     * @param result WIN 指出错误格 / MISS 指错
     */
    public record SpotGuess(int cell, String result, String qq, String userId, long at) {

        public static final String RESULT_WIN = "WIN";
        public static final String RESULT_MISS = "MISS";
    }
}
