package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一局群内共享的猜合成对局（反向玩法）：目标物品公开，
 * 玩家逐格猜 3x3 配方的原料；猜对揭示该物品占的全部格子，全部原料格揭示获胜。
 */
public class RecipeGame {

    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";

    /** 连续空猜达到该次数后获得一次提示机会。 */
    public static final int HINT_EMPTY_STREAK = 6;

    private final String id;
    private final String connectionId;
    private final String platform;
    private final String channelId;
    private final String targetId;
    /** 目标配方 3x3 网格（9 格，空位为 null）。 */
    private final List<String> grid;
    private final String startedByQq;
    private final String startedByUserId;
    private final long startedAt;
    private final Set<String> revealed = new LinkedHashSet<>();
    private final List<RecipeGuess> guesses = new ArrayList<>();
    private int emptyStreak;
    private int hintsUsed;
    private String status = STATUS_PLAYING;
    private String winnerQq;
    private String winnerUserId;
    private Long endedAt;

    public RecipeGame(String id, String connectionId, String platform, String channelId,
                      String targetId, List<String> grid,
                      String startedByQq, String startedByUserId, long startedAt) {
        this.id = id;
        this.connectionId = connectionId;
        this.platform = platform;
        this.channelId = channelId;
        this.targetId = targetId;
        // 网格含空位（null），不能用 List.copyOf
        this.grid = Collections.unmodifiableList(new ArrayList<>(grid));
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

    /** 该格是否为无原料的空位。 */
    public boolean isEmptySlot(int cell) {
        return grid.get(cell - 1) == null;
    }

    /** 该格的原料是否已揭示。 */
    public boolean isRevealedSlot(int cell) {
        String ingredient = grid.get(cell - 1);
        return ingredient != null && revealed.contains(ingredient);
    }

    /** 该格原料与提交物品是否一致。 */
    public boolean matches(int cell, String itemId) {
        return itemId != null && itemId.equals(grid.get(cell - 1));
    }

    /** 揭示某物品占用的全部格子，返回揭示的格子序号（1-9）。 */
    public List<Integer> revealItem(String itemId) {
        revealed.add(itemId);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (itemId.equals(grid.get(i))) {
                slots.add(i + 1);
            }
        }
        return slots;
    }

    /** 未揭示的原料物品 id 列表（去重）。 */
    public List<String> unrevealedIngredients() {
        Set<String> all = new LinkedHashSet<>();
        for (String slot : grid) {
            if (slot != null && !revealed.contains(slot)) {
                all.add(slot);
            }
        }
        return List.copyOf(all);
    }

    /** 全部原料格是否已揭示。 */
    public boolean isComplete() {
        return unrevealedIngredients().isEmpty();
    }

    public int revealedSlotCount() {
        int count = 0;
        for (String slot : grid) {
            if (slot != null && revealed.contains(slot)) {
                count++;
            }
        }
        return count;
    }

    public int ingredientSlotCount() {
        int count = 0;
        for (String slot : grid) {
            if (slot != null) {
                count++;
            }
        }
        return count;
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

    public void addGuess(RecipeGuess guess) {
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

    public List<String> getGrid() {
        return grid;
    }

    public Set<String> getRevealed() {
        return revealed;
    }

    public List<RecipeGuess> getGuesses() {
        return guesses;
    }

    public int getEmptyStreak() {
        return emptyStreak;
    }

    public int getHintsUsed() {
        return hintsUsed;
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
     * @param result HIT 猜对并揭示 / MISS 该格原料不是它 / EMPTY 该格本就无原料 /
     *               DUP 该格已揭示 / UNKNOWN 物品不存在（不记空猜）
     */
    public record RecipeGuess(int cell, String input, String matchedId, String matchedZh, String result,
                              String qq, String userId, long at) {

        public static final String RESULT_HIT = "HIT";
        public static final String RESULT_MISS = "MISS";
        public static final String RESULT_EMPTY = "EMPTY";
        public static final String RESULT_DUP = "DUP";
        public static final String RESULT_UNKNOWN = "UNKNOWN";
    }
}
