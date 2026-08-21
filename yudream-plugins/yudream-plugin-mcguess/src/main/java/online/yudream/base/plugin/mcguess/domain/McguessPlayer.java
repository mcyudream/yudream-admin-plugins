package online.yudream.base.plugin.mcguess.domain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 玩家战绩（需绑定系统账号），按游戏模式分别统计参与局数与胜场；
 * 另含图鉴收集进度、比大小（HOL）最佳连击与进行中的比大小对局状态。
 */
public class McguessPlayer {

    private final String userId;
    private String qq;
    private String nickname;
    private int itemPlayed;
    private int itemWins;
    private int mobPlayed;
    private int mobWins;
    private int recipePlayed;
    private int recipeWins;
    private int fogPlayed;
    private int fogWins;
    private int quizPlayed;
    private int quizWins;
    private int bingoPlayed;
    private int bingoWins;
    private int spotPlayed;
    private int spotWins;
    private int totalGuesses;
    /** 比大小历史最佳连击。 */
    private int holBest;
    /** 比大小进行中的两个物品 id（A 已知分数，B 待猜）；null 表示没有进行中的对局。 */
    private String holA;
    private String holB;
    /** 比大小当前连击数。 */
    private int holStreak;
    /** 图鉴：已收集的物品 id（按首次收集顺序）。 */
    private final Set<String> collection = new LinkedHashSet<>();
    private long updatedAt;

    public McguessPlayer(String userId) {
        this.userId = userId;
    }

    /** 记录一次有效猜测（填格 / 提交均计入）。 */
    public void recordGuess(long now) {
        totalGuesses++;
        updatedAt = now;
    }

    /** 对局结束时记录一次参与。 */
    public void recordPlayed(String mode, long now) {
        switch (mode) {
            case McguessMode.ITEM -> itemPlayed++;
            case McguessMode.MOB -> mobPlayed++;
            case McguessMode.RECIPE -> recipePlayed++;
            case McguessMode.FOG -> fogPlayed++;
            case McguessMode.QUIZ -> quizPlayed++;
            case McguessMode.BINGO -> bingoPlayed++;
            case McguessMode.SPOT -> spotPlayed++;
            default -> {
            }
        }
        updatedAt = now;
    }

    /** 对局结束时记录一次胜场（终结者）。 */
    public void recordWin(String mode, long now) {
        switch (mode) {
            case McguessMode.ITEM -> itemWins++;
            case McguessMode.MOB -> mobWins++;
            case McguessMode.RECIPE -> recipeWins++;
            case McguessMode.FOG -> fogWins++;
            case McguessMode.QUIZ -> quizWins++;
            case McguessMode.BINGO -> bingoWins++;
            case McguessMode.SPOT -> spotWins++;
            default -> {
            }
        }
        updatedAt = now;
    }

    public int played() {
        return itemPlayed + mobPlayed + recipePlayed + fogPlayed + quizPlayed + bingoPlayed + spotPlayed;
    }

    public int wins() {
        return itemWins + mobWins + recipeWins + fogWins + quizWins + bingoWins + spotWins;
    }

    /** 图鉴收集：首次收集返回 true。 */
    public boolean collect(String itemId) {
        boolean added = itemId != null && !itemId.isBlank() && collection.add(itemId);
        if (added) {
            updatedAt = System.currentTimeMillis();
        }
        return added;
    }

    public int collectionSize() {
        return collection.size();
    }

    public List<String> collectionItems() {
        return List.copyOf(collection);
    }

    // ---------------------------------------------------------------- 比大小（HOL）对局状态

    public boolean holInProgress() {
        return holA != null && holB != null;
    }

    /** 开一局新的比大小（连击清零由调用方根据语义决定）。 */
    public void startHol(String a, String b) {
        this.holA = a;
        this.holB = b;
    }

    /** 答对：B 变为新的 A，发新 B，连击 +1 并刷新最佳。 */
    public void advanceHol(String newB) {
        this.holA = this.holB;
        this.holB = newB;
        this.holStreak++;
        this.holBest = Math.max(this.holBest, this.holStreak);
        this.updatedAt = System.currentTimeMillis();
    }

    /** 答错：清空对局，连击归零（最佳保留）。 */
    public void clearHol() {
        this.holA = null;
        this.holB = null;
        this.holStreak = 0;
        this.updatedAt = System.currentTimeMillis();
    }

    /** 仅供仓储从持久化文档恢复统计（2.0.0 字段）。 */
    public void restoreStats(int itemPlayed, int itemWins, int mobPlayed, int mobWins,
                             int recipePlayed, int recipeWins, int totalGuesses, long updatedAt) {
        this.itemPlayed = itemPlayed;
        this.itemWins = itemWins;
        this.mobPlayed = mobPlayed;
        this.mobWins = mobWins;
        this.recipePlayed = recipePlayed;
        this.recipeWins = recipeWins;
        this.totalGuesses = totalGuesses;
        this.updatedAt = updatedAt;
    }

    /** 仅供仓储从持久化文档恢复 2.1.0 新增字段（新模式战绩、比大小、图鉴）。 */
    public void restoreExtras(int fogPlayed, int fogWins, int quizPlayed, int quizWins,
                              int bingoPlayed, int bingoWins, int spotPlayed, int spotWins,
                              int holBest, String holA, String holB, int holStreak,
                              Collection<String> collection) {
        this.fogPlayed = fogPlayed;
        this.fogWins = fogWins;
        this.quizPlayed = quizPlayed;
        this.quizWins = quizWins;
        this.bingoPlayed = bingoPlayed;
        this.bingoWins = bingoWins;
        this.spotPlayed = spotPlayed;
        this.spotWins = spotWins;
        this.holBest = holBest;
        this.holA = holA;
        this.holB = holB;
        this.holStreak = holStreak;
        this.collection.clear();
        if (collection != null) {
            collection.stream().filter(id -> id != null && !id.isBlank()).forEach(this.collection::add);
        }
    }

    public String getUserId() {
        return userId;
    }

    public String getQq() {
        return qq;
    }

    public void setQq(String qq) {
        this.qq = qq;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public int getItemPlayed() {
        return itemPlayed;
    }

    public int getItemWins() {
        return itemWins;
    }

    public int getMobPlayed() {
        return mobPlayed;
    }

    public int getMobWins() {
        return mobWins;
    }

    public int getRecipePlayed() {
        return recipePlayed;
    }

    public int getRecipeWins() {
        return recipeWins;
    }

    public int getFogPlayed() {
        return fogPlayed;
    }

    public int getFogWins() {
        return fogWins;
    }

    public int getQuizPlayed() {
        return quizPlayed;
    }

    public int getQuizWins() {
        return quizWins;
    }

    public int getBingoPlayed() {
        return bingoPlayed;
    }

    public int getBingoWins() {
        return bingoWins;
    }

    public int getSpotPlayed() {
        return spotPlayed;
    }

    public int getSpotWins() {
        return spotWins;
    }

    public int getTotalGuesses() {
        return totalGuesses;
    }

    public int getHolBest() {
        return holBest;
    }

    public String getHolA() {
        return holA;
    }

    public String getHolB() {
        return holB;
    }

    public int getHolStreak() {
        return holStreak;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }
}
