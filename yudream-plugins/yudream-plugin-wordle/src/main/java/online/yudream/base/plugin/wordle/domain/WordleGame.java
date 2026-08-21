package online.yudream.base.plugin.wordle.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一局群内共享的猜词对局。每个群（channelKey）同一时间最多一局进行中。
 */
public class WordleGame {

    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";

    private final String id;
    private final String connectionId;
    private final String platform;
    private final String channelId;
    private final WordleMode mode;
    private final String answer;
    private final boolean hardMode;
    private final int maxGuesses;
    private final String startedByQq;
    private final String startedByUserId;
    private final long startedAt;
    private final List<Guess> guesses = new ArrayList<>();
    private String status = STATUS_PLAYING;
    private String winnerQq;
    private String winnerUserId;
    private Long endedAt;

    public WordleGame(String id, String connectionId, String platform, String channelId, WordleMode mode,
                      String answer, boolean hardMode, String startedByQq, String startedByUserId, long startedAt) {
        this.id = id;
        this.connectionId = connectionId;
        this.platform = platform;
        this.channelId = channelId;
        this.mode = mode;
        this.answer = answer;
        this.hardMode = hardMode;
        this.maxGuesses = mode == WordleMode.IDIOM ? 10 + length() - 1 : 6 + length() - 1;
        this.startedByQq = startedByQq;
        this.startedByUserId = startedByUserId;
        this.startedAt = startedAt;
    }

    public String channelKey() {
        return channelKey(connectionId, channelId);
    }

    public static String channelKey(String connectionId, String channelId) {
        return connectionId + ":" + channelId;
    }

    public int length() {
        return answer.codePointCount(0, answer.length());
    }

    public boolean isPlaying() {
        return STATUS_PLAYING.equals(status);
    }

    public boolean hasGuessed(String word) {
        for (Guess guess : guesses) {
            if (guess.word().equals(word)) {
                return true;
            }
        }
        return false;
    }

    public void addGuess(Guess guess) {
        guesses.add(guess);
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

    public int remaining() {
        return maxGuesses - guesses.size();
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

    public WordleMode getMode() {
        return mode;
    }

    public String getAnswer() {
        return answer;
    }

    public boolean isHardMode() {
        return hardMode;
    }

    public int getMaxGuesses() {
        return maxGuesses;
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

    public List<Guess> getGuesses() {
        return guesses;
    }

    public String getStatus() {
        return status;
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
