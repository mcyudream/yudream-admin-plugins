package online.yudream.base.plugin.wordle.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 绑定系统用户的猜词战绩。仅记录已绑定账号的玩家，匿名猜测不计入。
 */
public class WordlePlayer {

    private final String userId;
    private String qq;
    private String nickname;
    private int englishPlayed;
    private int englishWins;
    private int idiomPlayed;
    private int idiomWins;
    private int currentStreak;
    private int bestStreak;
    private final Map<String, Integer> winDistribution = new LinkedHashMap<>();
    private long updatedAt;

    public WordlePlayer(String userId) {
        this.userId = userId;
    }

    public void recordPlayed(WordleMode mode, long at) {
        if (mode == WordleMode.ENGLISH) {
            englishPlayed++;
        } else {
            idiomPlayed++;
        }
        this.updatedAt = at;
    }

    public void recordWin(WordleMode mode, int guessesUsed, long at) {
        if (mode == WordleMode.ENGLISH) {
            englishWins++;
        } else {
            idiomWins++;
        }
        currentStreak++;
        bestStreak = Math.max(bestStreak, currentStreak);
        winDistribution.merge(String.valueOf(guessesUsed), 1, Integer::sum);
        this.updatedAt = at;
    }

    public void recordLoss(long at) {
        currentStreak = 0;
        this.updatedAt = at;
    }

    public int totalPlayed() {
        return englishPlayed + idiomPlayed;
    }

    public int totalWins() {
        return englishWins + idiomWins;
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

    public int getEnglishPlayed() {
        return englishPlayed;
    }

    public void setEnglishPlayed(int englishPlayed) {
        this.englishPlayed = englishPlayed;
    }

    public int getEnglishWins() {
        return englishWins;
    }

    public void setEnglishWins(int englishWins) {
        this.englishWins = englishWins;
    }

    public int getIdiomPlayed() {
        return idiomPlayed;
    }

    public void setIdiomPlayed(int idiomPlayed) {
        this.idiomPlayed = idiomPlayed;
    }

    public int getIdiomWins() {
        return idiomWins;
    }

    public void setIdiomWins(int idiomWins) {
        this.idiomWins = idiomWins;
    }

    public int getCurrentStreak() {
        return currentStreak;
    }

    public void setCurrentStreak(int currentStreak) {
        this.currentStreak = currentStreak;
    }

    public int getBestStreak() {
        return bestStreak;
    }

    public void setBestStreak(int bestStreak) {
        this.bestStreak = bestStreak;
    }

    public Map<String, Integer> getWinDistribution() {
        return winDistribution;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
