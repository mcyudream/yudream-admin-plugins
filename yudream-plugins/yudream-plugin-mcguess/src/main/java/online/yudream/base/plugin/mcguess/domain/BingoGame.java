package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一局群内共享的 MC 宾果对局：5x5 共 {@value #CELL_COUNT} 格不同物品，
 * 玩家报物品名点亮对应格子（智能匹配），率先点亮任意一整行 / 整列 / 对角线者获胜。
 */
public class BingoGame implements ChannelGame {

    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";

    public static final int BOARD_SIZE = 5;
    public static final int CELL_COUNT = BOARD_SIZE * BOARD_SIZE;

    /** 全部连线（0 起格子序号）：5 行 + 5 列 + 2 对角线。 */
    public static final List<List<Integer>> LINES = buildLines();

    private static List<List<Integer>> buildLines() {
        List<List<Integer>> lines = new ArrayList<>();
        for (int r = 0; r < BOARD_SIZE; r++) {
            List<Integer> row = new ArrayList<>();
            for (int c = 0; c < BOARD_SIZE; c++) {
                row.add(r * BOARD_SIZE + c);
            }
            lines.add(row);
        }
        for (int c = 0; c < BOARD_SIZE; c++) {
            List<Integer> col = new ArrayList<>();
            for (int r = 0; r < BOARD_SIZE; r++) {
                col.add(r * BOARD_SIZE + c);
            }
            lines.add(col);
        }
        List<Integer> diagonal = new ArrayList<>();
        List<Integer> antiDiagonal = new ArrayList<>();
        for (int i = 0; i < BOARD_SIZE; i++) {
            diagonal.add(i * BOARD_SIZE + i);
            antiDiagonal.add(i * BOARD_SIZE + (BOARD_SIZE - 1 - i));
        }
        lines.add(diagonal);
        lines.add(antiDiagonal);
        return List.copyOf(lines);
    }

    private final String id;
    private final String connectionId;
    private final String platform;
    private final String channelId;
    /** 25 格物品 id（互不重复）。 */
    private final List<String> cells;
    private final String startedByQq;
    private final String startedByUserId;
    private final long startedAt;
    /** 每格点亮者 userId（未点亮为 ""）。 */
    private final List<String> claimers = new ArrayList<>();
    private final List<String> claimerQqs = new ArrayList<>();
    private final List<BingoGuess> guesses = new ArrayList<>();
    /** 获胜连线（0 起格子序号）；未获胜为空。 */
    private List<Integer> winCells = List.of();
    private String status = STATUS_PLAYING;
    private String winnerQq;
    private String winnerUserId;
    private Long endedAt;

    public BingoGame(String id, String connectionId, String platform, String channelId,
                     List<String> cells, String startedByQq, String startedByUserId, long startedAt) {
        this.id = id;
        this.connectionId = connectionId;
        this.platform = platform;
        this.channelId = channelId;
        this.cells = List.copyOf(cells);
        this.startedByQq = startedByQq;
        this.startedByUserId = startedByUserId;
        this.startedAt = startedAt;
        for (int i = 0; i < CELL_COUNT; i++) {
            claimers.add("");
            claimerQqs.add("");
        }
    }

    public static String channelKey(String connectionId, String channelId) {
        return connectionId + ":" + channelId;
    }

    public boolean isPlaying() {
        return STATUS_PLAYING.equals(status);
    }

    /** 格子序号是否合法（1-25）。 */
    public static boolean isValidCell(int cell) {
        return cell >= 1 && cell <= CELL_COUNT;
    }

    /** 物品所在的格子序号（1-25）；不在盘上返回 -1。 */
    public int cellIndexOf(String itemId) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).equals(itemId)) {
                return i + 1;
            }
        }
        return -1;
    }

    public boolean isClaimed(int cell) {
        return !claimers.get(cell - 1).isEmpty();
    }

    public String claimerOf(int cell) {
        return claimers.get(cell - 1);
    }

    public String claimerQqOf(int cell) {
        return claimerQqs.get(cell - 1);
    }

    /** 点亮某格。 */
    public void claim(int cell, String qq, String userId) {
        claimers.set(cell - 1, userId == null ? "" : userId);
        claimerQqs.set(cell - 1, qq == null ? "" : qq);
    }

    public int claimedCount() {
        int count = 0;
        for (String claimer : claimers) {
            if (!claimer.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** 当前是否出现完整连线；有则返回连线格子序号（0 起），无返回 null。 */
    public List<Integer> findCompletedLine() {
        for (List<Integer> line : LINES) {
            boolean complete = true;
            for (int index : line) {
                if (claimers.get(index).isEmpty()) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return line;
            }
        }
        return null;
    }

    public void addGuess(BingoGuess guess) {
        guesses.add(guess);
    }

    public void win(String winnerQq, String winnerUserId, List<Integer> winCells, long endedAt) {
        this.status = STATUS_WON;
        this.winnerQq = winnerQq;
        this.winnerUserId = winnerUserId;
        this.winCells = winCells == null ? List.of() : List.copyOf(winCells);
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

    public List<String> getCells() {
        return cells;
    }

    public List<Integer> getWinCells() {
        return winCells;
    }

    public List<BingoGuess> getGuesses() {
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
     * 一次认领记录。
     *
     * @param result CLAIM 点亮格子 / MISS 物品不在盘上 / DUP 该格已点亮
     * @param cell   点亮的格子序号（1-25）；不在盘上为 null
     */
    public record BingoGuess(String input, String matchedId, String matchedZh, String result, Integer cell,
                             String qq, String userId, long at) {

        public static final String RESULT_CLAIM = "CLAIM";
        public static final String RESULT_MISS = "MISS";
        public static final String RESULT_DUP = "DUP";
    }
}
