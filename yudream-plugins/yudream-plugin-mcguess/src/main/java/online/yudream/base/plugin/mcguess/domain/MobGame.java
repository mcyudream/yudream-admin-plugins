package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一局群内共享的猜生物对局（填格子）：3 行条件 × 3 列条件，
 * 生物须同时满足行与列条件且同盘不重复；填错扣 1 心，心耗尽或投降失败，填满 9 格获胜。
 */
public class MobGame {

    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";

    /** 初始心数。 */
    public static final int MAX_HEARTS = 6;
    /** 棋盘格数。 */
    public static final int CELL_COUNT = 9;

    private final String id;
    private final String connectionId;
    private final String platform;
    private final String channelId;
    private final List<String> rowConds;
    private final List<String> colConds;
    /** 一组可行答案，失败揭晓时展示。 */
    private final List<String> solution;
    private final String startedByQq;
    private final String startedByUserId;
    private final long startedAt;
    private final List<String> cells = new ArrayList<>();
    private final List<MobGuess> guesses = new ArrayList<>();
    private int hearts = MAX_HEARTS;
    private String status = STATUS_PLAYING;
    private String winnerQq;
    private String winnerUserId;
    private Long endedAt;

    public MobGame(String id, String connectionId, String platform, String channelId,
                   List<String> rowConds, List<String> colConds, List<String> solution,
                   String startedByQq, String startedByUserId, long startedAt) {
        this.id = id;
        this.connectionId = connectionId;
        this.platform = platform;
        this.channelId = channelId;
        this.rowConds = List.copyOf(rowConds);
        this.colConds = List.copyOf(colConds);
        this.solution = List.copyOf(solution);
        this.startedByQq = startedByQq;
        this.startedByUserId = startedByUserId;
        this.startedAt = startedAt;
        for (int i = 0; i < CELL_COUNT; i++) {
            cells.add(null);
        }
    }

    public static String channelKey(String connectionId, String channelId) {
        return connectionId + ":" + channelId;
    }

    public boolean isPlaying() {
        return STATUS_PLAYING.equals(status);
    }

    /** 格子序号是否合法（1-9）。 */
    public static boolean isValidCell(int cell) {
        return cell >= 1 && cell <= CELL_COUNT;
    }

    public boolean isFilled(int cell) {
        return cells.get(cell - 1) != null;
    }

    public boolean hasUsed(String mobId) {
        return cells.contains(mobId);
    }

    /** 该生物是否满足格子的行 + 列条件。 */
    public boolean satisfies(int cell, McMobCatalog.McMob mob) {
        String row = rowConds.get((cell - 1) / 3);
        String col = colConds.get((cell - 1) % 3);
        return mob.cond().contains(row) && mob.cond().contains(col);
    }

    /** 填入格子。 */
    public void fill(int cell, String mobId) {
        cells.set(cell - 1, mobId);
    }

    /** 仅供仓储从持久化文档恢复格子。 */
    public void restoreCells(List<String> filled) {
        for (int i = 0; i < CELL_COUNT && i < filled.size(); i++) {
            cells.set(i, filled.get(i));
        }
    }

    public void loseHeart() {
        this.hearts = Math.max(0, hearts - 1);
    }

    /** 仅供仓储恢复心数。 */
    public void restoreHearts(int hearts) {
        this.hearts = hearts;
    }

    public void addGuess(MobGuess guess) {
        guesses.add(guess);
    }

    public int filledCount() {
        int count = 0;
        for (String cell : cells) {
            if (cell != null) {
                count++;
            }
        }
        return count;
    }

    public boolean isComplete() {
        return filledCount() == CELL_COUNT;
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

    public List<String> getRowConds() {
        return rowConds;
    }

    public List<String> getColConds() {
        return colConds;
    }

    public List<String> getSolution() {
        return solution;
    }

    public List<String> getCells() {
        return cells;
    }

    public List<MobGuess> getGuesses() {
        return guesses;
    }

    public int getHearts() {
        return hearts;
    }

    public String getStatus() {
        return status;
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
     * 一次填格记录。
     *
     * @param result FILLED 填对占格 / WRONG 不满足条件扣心 / DUPLICATE 生物已使用 /
     *               OCCUPIED 格子已填 / UNKNOWN 生物不存在（后三种不扣心）
     */
    public record MobGuess(int cell, String input, String mobId, String mobZh, String result,
                           String qq, String userId, long at) {

        public static final String RESULT_FILLED = "FILLED";
        public static final String RESULT_WRONG = "WRONG";
        public static final String RESULT_DUPLICATE = "DUPLICATE";
        public static final String RESULT_OCCUPIED = "OCCUPIED";
        public static final String RESULT_UNKNOWN = "UNKNOWN";
    }
}
