package online.yudream.base.plugin.pony.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一局群内共享的小马归位对局。每个群（channelKey）同一时间最多一局进行中。
 * 坐标对外 1 起（列 1..N，行 1..N，第 1 行在底部），内部统一 0 起、按行优先索引。
 */
public class PonyGame {

    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";
    public static final int MAX_LIVES = 3;

    private final String id;
    private final String connectionId;
    private final String platform;
    private final String channelId;
    private final int size;
    private final List<Integer> regions;
    private final List<Integer> solution;
    private final String startedByQq;
    private final String startedByUserId;
    private final long startedAt;
    private final Set<Integer> marks = new LinkedHashSet<>();
    private final List<HorsePlacement> horses = new ArrayList<>();
    private int lives = MAX_LIVES;
    private int mistakes;
    private String status = STATUS_PLAYING;
    private String winnerQq;
    private String winnerUserId;
    private Long endedAt;

    public PonyGame(String id, String connectionId, String platform, String channelId, PonyPuzzle puzzle,
                    String startedByQq, String startedByUserId, long startedAt) {
        this.id = id;
        this.connectionId = connectionId;
        this.platform = platform;
        this.channelId = channelId;
        this.size = puzzle.size();
        this.regions = toList(puzzle.regions());
        this.solution = toList(puzzle.solution());
        this.startedByQq = startedByQq;
        this.startedByUserId = startedByUserId;
        this.startedAt = startedAt;
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> list = new ArrayList<>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    public String channelKey() {
        return channelKey(connectionId, channelId);
    }

    public static String channelKey(String connectionId, String channelId) {
        return connectionId + ":" + channelId;
    }

    public int index(int row, int col) {
        return row * size + col;
    }

    public int regionAt(int row, int col) {
        return regions.get(index(row, col));
    }

    public boolean isSolutionCell(int row, int col) {
        return solution.get(row) == col;
    }

    public boolean isPlaying() {
        return STATUS_PLAYING.equals(status);
    }

    public boolean horseAt(int index) {
        for (HorsePlacement horse : horses) {
            if (horse.cell() == index) {
                return true;
            }
        }
        return false;
    }

    /**
     * 切换 × 标记；已有小马的格子不可标记。返回切换后是否处于标记状态。
     */
    public boolean toggleMark(int row, int col) {
        int index = index(row, col);
        if (horseAt(index)) {
            return false;
        }
        if (!marks.remove(index)) {
            marks.add(index);
            return true;
        }
        return false;
    }

    /**
     * 放置正确的小马：记录放置，并自动把该格一圈、所在行、所在列与同色区域全部标记为 ×。
     */
    public void placeHorse(int row, int col, String qq, String userId, long at) {
        int index = index(row, col);
        marks.remove(index);
        horses.add(new HorsePlacement(index, qq, userId, at));
        int region = regions.get(index);
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                int cell = index(r, c);
                boolean ring = Math.abs(r - row) <= 1 && Math.abs(c - col) <= 1;
                if (r == row || c == col || ring || regions.get(cell) == region) {
                    if (!horseAt(cell)) {
                        marks.add(cell);
                    }
                }
            }
        }
    }

    /**
     * 放错位置：扣 1 点生命并累计失误。
     */
    public void miss() {
        lives--;
        mistakes++;
    }

    public int remaining() {
        return size - horses.size();
    }

    /**
     * 参与过正确放置的去重用户（仅绑定账号计入战绩）。
     */
    public Set<String> participants() {
        Set<String> ids = new LinkedHashSet<>();
        for (HorsePlacement horse : horses) {
            if (horse.userId() != null && !horse.userId().isBlank()) {
                ids.add(horse.userId());
            }
        }
        return ids;
    }

    public String lastQqOf(String userId) {
        String qq = null;
        for (HorsePlacement horse : horses) {
            if (userId.equals(horse.userId())) {
                qq = horse.qq();
            }
        }
        return qq;
    }

    public void win(String qq, String userId, long at) {
        this.status = STATUS_WON;
        this.winnerQq = qq;
        this.winnerUserId = userId;
        this.endedAt = at;
    }

    public void lose(long at) {
        this.status = STATUS_LOST;
        this.endedAt = at;
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

    public int getSize() {
        return size;
    }

    public List<Integer> getRegions() {
        return regions;
    }

    public List<Integer> getSolution() {
        return solution;
    }

    public Set<Integer> getMarks() {
        return marks;
    }

    public List<HorsePlacement> getHorses() {
        return horses;
    }

    public int getLives() {
        return lives;
    }

    public void setLives(int lives) {
        this.lives = lives;
    }

    public int getMistakes() {
        return mistakes;
    }

    public void setMistakes(int mistakes) {
        this.mistakes = mistakes;
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

    public long getStartedAt() {
        return startedAt;
    }

    public String getWinnerQq() {
        return winnerQq;
    }

    public String getWinnerUserId() {
        return winnerUserId;
    }

    public Long getEndedAt() {
        return endedAt;
    }
}
