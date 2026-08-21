package online.yudream.base.plugin.mcguess.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一局群内共享的合成快答对局：固定 {@value #QUESTION_COUNT} 道选择题，
 * 每题问「合成 1 个 X 总共需要几个 Y」（答案取自合成树出现次数），四个选项；
 * 每题第一个答对的人得 1 分，答错的人本题不得再答；全部题目答完结算，得分最高者获胜。
 */
public class QuizGame implements ChannelGame {

    public static final String STATUS_PLAYING = "PLAYING";
    public static final String STATUS_WON = "WON";
    public static final String STATUS_LOST = "LOST";

    public static final int QUESTION_COUNT = 5;
    public static final int CHOICE_COUNT = 4;

    private final String id;
    private final String connectionId;
    private final String platform;
    private final String channelId;
    private final List<Question> questions;
    private final String startedByQq;
    private final String startedByUserId;
    private final long startedAt;
    /** 每题的答对者（未答出为 null）。 */
    private final List<String> correctQqs = new ArrayList<>();
    private final List<String> correctUserIds = new ArrayList<>();
    /** 每题已答错的用户（本题不得再答）。 */
    private final List<Set<String>> wrongUsers = new ArrayList<>();
    private final List<QuizGuess> guesses = new ArrayList<>();
    private String status = STATUS_PLAYING;
    private String winnerQq;
    private String winnerUserId;
    private Long endedAt;

    public QuizGame(String id, String connectionId, String platform, String channelId,
                    List<Question> questions, String startedByQq, String startedByUserId, long startedAt) {
        this.id = id;
        this.connectionId = connectionId;
        this.platform = platform;
        this.channelId = channelId;
        this.questions = List.copyOf(questions);
        this.startedByQq = startedByQq;
        this.startedByUserId = startedByUserId;
        this.startedAt = startedAt;
        for (int i = 0; i < this.questions.size(); i++) {
            correctQqs.add(null);
            correctUserIds.add(null);
            wrongUsers.add(new LinkedHashSet<>());
        }
    }

    public static String channelKey(String connectionId, String channelId) {
        return connectionId + ":" + channelId;
    }

    public boolean isPlaying() {
        return STATUS_PLAYING.equals(status);
    }

    public int questionCount() {
        return questions.size();
    }

    /** 当前待答题号（0 起）；全部答出返回 -1。 */
    public int currentQuestionIndex() {
        for (int i = 0; i < questions.size(); i++) {
            if (correctUserIds.get(i) == null) {
                return i;
            }
        }
        return -1;
    }

    public boolean isComplete() {
        return currentQuestionIndex() == -1;
    }

    public boolean isSolved(int index) {
        return correctUserIds.get(index) != null;
    }

    public boolean hasAnsweredWrong(int index, String userId) {
        return userId != null && wrongUsers.get(index).contains(userId);
    }

    public void markWrong(int index, String userId) {
        if (userId != null) {
            wrongUsers.get(index).add(userId);
        }
    }

    public void solve(int index, String qq, String userId) {
        correctQqs.set(index, qq);
        correctUserIds.set(index, userId);
    }

    public String correctQqOf(int index) {
        return correctQqs.get(index);
    }

    public String correctUserIdOf(int index) {
        return correctUserIds.get(index);
    }

    /** 该题已答错的用户 id 列表（仅供仓储持久化）。 */
    public List<String> wrongUsersOf(int index) {
        return List.copyOf(wrongUsers.get(index));
    }

    /** 计分板：按得分降序、同分按首次答对题号升序。 */
    public List<QuizScore> scoreboard() {
        Map<String, int[]> scores = new LinkedHashMap<>();
        Map<String, String> qqs = new LinkedHashMap<>();
        Map<String, Integer> firstSolve = new LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            String userId = correctUserIds.get(i);
            if (userId == null) {
                continue;
            }
            scores.computeIfAbsent(userId, key -> new int[1])[0]++;
            qqs.putIfAbsent(userId, correctQqs.get(i));
            firstSolve.putIfAbsent(userId, i);
        }
        List<QuizScore> rows = new ArrayList<>();
        scores.forEach((userId, score) -> rows.add(new QuizScore(userId, qqs.get(userId), score[0])));
        rows.sort(Comparator.comparingInt(QuizScore::score).reversed()
                .thenComparingInt(row -> firstSolve.getOrDefault(row.userId(), Integer.MAX_VALUE)));
        return rows;
    }

    /** 得分最高者（同分取最早答对者）；无人答对时返回 null。 */
    public QuizScore topScorer() {
        List<QuizScore> rows = scoreboard();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void addGuess(QuizGuess guess) {
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

    /** 仅供仓储从持久化文档恢复每题状态。 */
    public void restoreQuestionState(int index, String correctQq, String correctUserId, Set<String> wrong) {
        correctQqs.set(index, correctQq);
        correctUserIds.set(index, correctUserId);
        wrongUsers.get(index).clear();
        if (wrong != null) {
            wrongUsers.get(index).addAll(wrong);
        }
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

    public List<Question> getQuestions() {
        return questions;
    }

    public List<QuizGuess> getGuesses() {
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
     * 一道快答题。
     *
     * @param targetId     合成目标物品 id
     * @param ingredientId 被问的原料物品 id
     * @param answer       正确答案（该原料在目标合成树中的总出现次数）
     * @param choices      四个选项（含答案，已乱序）
     */
    public record Question(String targetId, String ingredientId, int answer, List<Integer> choices) {
    }

    /** 计分板行。 */
    public record QuizScore(String userId, String qq, int score) {
    }

    /**
     * 一次作答记录。
     *
     * @param question 题号（1 起）
     * @param choice   选项序号（1-4）
     */
    public record QuizGuess(int question, int choice, boolean correct,
                            String qq, String userId, long at) {
    }
}
