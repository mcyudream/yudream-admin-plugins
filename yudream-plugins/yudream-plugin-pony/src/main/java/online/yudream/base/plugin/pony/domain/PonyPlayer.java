package online.yudream.base.plugin.pony.domain;

/**
 * 绑定系统用户的小马归位战绩。仅记录已绑定账号的玩家，匿名操作不计入。
 */
public class PonyPlayer {

    private final String userId;
    private String qq;
    private String nickname;
    private int played;
    private int wins;
    private int horsesPlaced;
    private int currentStreak;
    private int bestStreak;
    private long updatedAt;

    public PonyPlayer(String userId) {
        this.userId = userId;
    }

    public void recordPlayed(long at) {
        played++;
        this.updatedAt = at;
    }

    public void recordWin(int placed, long at) {
        wins++;
        horsesPlaced += placed;
        currentStreak++;
        bestStreak = Math.max(bestStreak, currentStreak);
        this.updatedAt = at;
    }

    public void recordLoss(int placed, long at) {
        horsesPlaced += placed;
        currentStreak = 0;
        this.updatedAt = at;
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

    public int getPlayed() {
        return played;
    }

    public void setPlayed(int played) {
        this.played = played;
    }

    public int getWins() {
        return wins;
    }

    public void setWins(int wins) {
        this.wins = wins;
    }

    public int getHorsesPlaced() {
        return horsesPlaced;
    }

    public void setHorsesPlaced(int horsesPlaced) {
        this.horsesPlaced = horsesPlaced;
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

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
