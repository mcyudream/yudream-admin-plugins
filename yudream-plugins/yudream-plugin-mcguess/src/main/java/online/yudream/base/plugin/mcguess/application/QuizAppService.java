package online.yudream.base.plugin.mcguess.application;

import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McguessMode;
import online.yudream.base.plugin.mcguess.domain.QuizGame;
import online.yudream.base.plugin.mcguess.domain.QuizGame.Question;
import online.yudream.base.plugin.mcguess.domain.QuizGame.QuizGuess;
import online.yudream.base.plugin.mcguess.domain.QuizGame.QuizScore;
import online.yudream.base.plugin.mcguess.domain.QuizGameRepository;
import online.yudream.base.plugin.mcguess.domain.QuizGenerator;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 合成计数快答用例编排（群回合制）：一局固定 5 道选择题，每题问
 * 「合成 1 个 X 总共需要几个 Y」，四个选项；每题第一个答对的人得 1 分，
 * 答错的人本题不得再答；5 题全部答完结算，得分最高者获胜。
 */
public class QuizAppService {

    private static final int RECORD_LIMIT = 10;
    private static final List<String> LETTERS = List.of("A", "B", "C", "D");

    private final QuizGameRepository games;
    private final McCatalog catalog;
    private final IconSupport icons;
    private final McguessSupport support;
    private final QuizGenerator generator;
    private final Random random = new Random();

    public QuizAppService(QuizGameRepository games, McCatalog catalog, IconSupport icons, McguessSupport support) {
        this.games = games;
        this.catalog = catalog;
        this.icons = icons;
        this.support = support;
        this.generator = new QuizGenerator(catalog);
    }

    // ---------------------------------------------------------------- 群聊指令用例

    /**
     * /快答 [A-D]：无参数时查看局面；带选项时作答当前题目。
     */
    public String answer(PluginEvent event, Long userId, String choiceInput) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中参与快答。";
        }
        if (choiceInput == null || choiceInput.isBlank()) {
            return status(event);
        }
        Integer choiceIndex = parseChoice(choiceInput.trim());
        if (choiceIndex == null) {
            return "选项只认 A/B/C/D（或 1-4），例如 /快答 B。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            String userIdString = userIdString(userId);
            QuizGame game = activeGame(event, userIdString);
            int questionIndex = game.currentQuestionIndex();
            if (questionIndex < 0) {
                return "本局快答 5 题已全部答出。发送 /快答 开始新一局！";
            }
            if (game.hasAnsweredWrong(questionIndex, userIdString)) {
                return "本题你已经答错过一次了，机会留给其他人！";
            }
            Question question = game.getQuestions().get(questionIndex);
            int chosen = question.choices().get(choiceIndex);
            boolean correct = chosen == question.answer();
            long now = System.currentTimeMillis();
            game.addGuess(new QuizGuess(questionIndex + 1, choiceIndex + 1, correct, event.userId(), userIdString, now));
            String zh = "第 " + (questionIndex + 1) + " 题";
            if (correct) {
                game.solve(questionIndex, event.userId(), userIdString);
                games.save(game);
                support.recordGuess(userIdString, event.userId());
                List<String> added = support.collect(userIdString, event.userId(), List.of(question.targetId()));
                if (game.isComplete()) {
                    QuizScore top = game.topScorer();
                    game.win(top == null ? event.userId() : top.qq(),
                            top == null ? userIdString : top.userId(), now);
                    games.save(game);
                    support.recordGameEnd(McguessMode.QUIZ, participants(game),
                            top == null ? null : top.userId());
                    return "✅ QQ " + event.userId() + " 答对" + zh + "（正确答案是 " + question.answer() + "）！"
                            + collectionSuffix(added)
                            + "\n🏁 5 题全部答出，本局快答结束！" + scoreboardText(game)
                            + "\n发送 /快答 开始新一局！";
                }
                return "✅ QQ " + event.userId() + " 答对" + zh + "（正确答案是 " + question.answer()
                        + "），+1 分！" + collectionSuffix(added)
                        + "\n进入第 " + (questionIndex + 2) + "/5 题：合成 1 个「" + zhOf(nextTarget(game))
                        + "」需要几个「" + zhOf(nextIngredient(game)) + "」？发送 /快答 A-D 抢答。";
            }
            game.markWrong(questionIndex, userIdString);
            games.save(game);
            support.recordGuess(userIdString, event.userId());
            return "❌ QQ " + event.userId() + " 答错" + zh + "！本题你不能再答，机会留给其他人。";
        }
    }

    public String status(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            QuizGame game = latestGame(event);
            if (!game.isPlaying()) {
                return "🏁 上一局快答已结束。" + scoreboardText(game) + "\n发送 /快答 开始新一局！";
            }
            int index = game.currentQuestionIndex();
            Question question = game.getQuestions().get(index);
            return "⚡ MC 快答进行中：第 " + (index + 1) + "/" + game.questionCount() + " 题——"
                    + "合成 1 个「" + zhOf(question.targetId()) + "」总共需要几个「" + zhOf(question.ingredientId())
                    + "」？\n选项：" + choicesText(question)
                    + "\n发送 /快答 A-D 抢答，/结束快答 投降。" + scoreboardText(game);
        }
    }

    public String surrender(PluginEvent event) {
        if (event.channelId() == null || event.connectionId() == null) {
            return "仅支持在群聊中操作。";
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            Optional<QuizGame> found = games.findActive(event.connectionId(), event.channelId());
            if (found.isEmpty()) {
                return "当前没有进行中的快答对局，发送 /快答 开始新一局！";
            }
            QuizGame game = found.get();
            game.lose(System.currentTimeMillis());
            games.save(game);
            support.recordGameEnd(McguessMode.QUIZ, participants(game), null);
            StringBuilder answers = new StringBuilder("🏳️ 本局快答已结束。答案回顾：");
            List<Question> questions = game.getQuestions();
            for (int i = 0; i < questions.size(); i++) {
                Question question = questions.get(i);
                answers.append("\n第 ").append(i + 1).append(" 题：「").append(zhOf(question.targetId()))
                        .append("」×「").append(zhOf(question.ingredientId())).append("」= ")
                        .append(question.answer());
            }
            return answers.append(scoreboardText(game)).append("\n发送 /快答 开始新一局！").toString();
        }
    }

    // ---------------------------------------------------------------- 棋盘图片渲染

    public Map<String, Object> boardVariables(PluginEvent event, String banner) {
        if (event.channelId() == null || event.connectionId() == null) {
            return null;
        }
        synchronized (support.lockFor(event.connectionId(), event.channelId())) {
            QuizGame game = latestGame(event);
            boolean won = QuizGame.STATUS_WON.equals(game.getStatus());
            boolean lost = QuizGame.STATUS_LOST.equals(game.getStatus());
            boolean ended = won || lost;

            List<Map<String, Object>> questionRows = new ArrayList<>();
            List<Question> questions = game.getQuestions();
            for (int i = 0; i < questions.size(); i++) {
                Question question = questions.get(i);
                boolean solved = game.isSolved(i);
                boolean current = game.isPlaying() && game.currentQuestionIndex() == i;
                Map<String, Object> row = new HashMap<>();
                row.put("index", i + 1);
                row.put("targetZh", zhOf(question.targetId()));
                row.put("targetIcon", icons.dataUri(question.targetId()));
                row.put("ingredientZh", zhOf(question.ingredientId()));
                row.put("ingredientIcon", icons.dataUri(question.ingredientId()));
                row.put("answer", solved || ended ? question.answer() : null);
                row.put("solved", solved);
                row.put("current", current);
                row.put("correctQq", game.correctQqOf(i));
                if (current || (ended && !solved)) {
                    List<Map<String, Object>> choiceRows = new ArrayList<>();
                    List<Integer> choices = question.choices();
                    for (int c = 0; c < choices.size(); c++) {
                        Map<String, Object> choice = new HashMap<>();
                        choice.put("letter", LETTERS.get(c));
                        choice.put("value", choices.get(c));
                        choice.put("hit", ended && choices.get(c) == question.answer());
                        choiceRows.add(choice);
                    }
                    row.put("choices", choiceRows);
                }
                questionRows.add(row);
            }

            Map<String, Object> variables = new HashMap<>();
            variables.put("title", "MC 快答");
            variables.put("subtitle", "合成计数抢答 · 每题首答 +1 分 · JE 1.20.5");
            variables.put("questions", questionRows);
            variables.put("progress", game.isPlaying()
                    ? "第 " + (game.currentQuestionIndex() + 1) + "/" + game.questionCount() + " 题"
                    : game.questionCount() + "/" + game.questionCount() + " 题");
            variables.put("scores", scoreRows(game));
            variables.put("won", won);
            variables.put("lost", lost);
            variables.put("ended", ended);
            variables.put("winnerQq", game.getWinnerQq());
            variables.put("records", recordRows(game));
            variables.put("banner", banner);
            return variables;
        }
    }

    // ---------------------------------------------------------------- 内部支撑

    private QuizGame activeGame(PluginEvent event, String startedByUserId) {
        Optional<QuizGame> found = games.findActive(event.connectionId(), event.channelId());
        if (found.isPresent()) {
            return found.get();
        }
        QuizGame game = new QuizGame(UUID.randomUUID().toString(),
                event.connectionId(), event.platform(), event.channelId(), generator.generate(random),
                event.userId(), startedByUserId, System.currentTimeMillis());
        games.save(game);
        return game;
    }

    private QuizGame latestGame(PluginEvent event) {
        return games.findLatest(event.connectionId(), event.channelId()).orElseGet(() -> activeGame(event, null));
    }

    /** A-D / a-d / 1-4 → 0 起选项下标；无法解析返回 null。 */
    private Integer parseChoice(String input) {
        String upper = input.toUpperCase();
        for (int i = 0; i < LETTERS.size(); i++) {
            if (LETTERS.get(i).equals(upper)) {
                return i;
            }
        }
        try {
            int value = Integer.parseInt(upper);
            if (value >= 1 && value <= QuizGame.CHOICE_COUNT) {
                return value - 1;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private String nextTarget(QuizGame game) {
        return game.getQuestions().get(game.currentQuestionIndex()).targetId();
    }

    private String nextIngredient(QuizGame game) {
        return game.getQuestions().get(game.currentQuestionIndex()).ingredientId();
    }

    private String choicesText(Question question) {
        StringBuilder text = new StringBuilder();
        List<Integer> choices = question.choices();
        for (int i = 0; i < choices.size(); i++) {
            if (i > 0) {
                text.append("　");
            }
            text.append(LETTERS.get(i)).append(". ").append(choices.get(i));
        }
        return text.toString();
    }

    private String scoreboardText(QuizGame game) {
        List<QuizScore> rows = game.scoreboard();
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n📊 计分板：");
        for (QuizScore row : rows) {
            text.append("\nQQ ").append(row.qq()).append("：").append(row.score()).append(" 分");
        }
        return text.toString();
    }

    private List<Map<String, Object>> scoreRows(QuizGame game) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (QuizScore score : game.scoreboard()) {
            Map<String, Object> row = new HashMap<>();
            row.put("rank", rank++);
            row.put("qq", score.qq());
            row.put("score", score.score());
            rows.add(row);
        }
        return rows;
    }

    private List<McguessSupport.Participant> participants(QuizGame game) {
        List<McguessSupport.Participant> participants = new ArrayList<>();
        for (QuizGuess guess : game.getGuesses()) {
            participants.add(new McguessSupport.Participant(guess.userId(), guess.qq()));
        }
        return participants;
    }

    private List<Map<String, Object>> recordRows(QuizGame game) {
        List<QuizGuess> guesses = game.getGuesses();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = guesses.size() - 1; i >= 0 && rows.size() < RECORD_LIMIT; i--) {
            QuizGuess guess = guesses.get(i);
            Map<String, Object> row = new HashMap<>();
            row.put("qq", guess.qq());
            row.put("label", "第 " + guess.question() + " 题选 " + LETTERS.get(guess.choice() - 1));
            row.put("cls", guess.correct() ? "found" : "miss");
            rows.add(row);
        }
        return rows;
    }

    private String collectionSuffix(List<String> added) {
        if (added.isEmpty()) {
            return "";
        }
        return "\n🎴 图鉴新增：" + added.stream().map(id -> "「" + zhOf(id) + "」").collect(Collectors.joining(""));
    }

    private String zhOf(String itemId) {
        return catalog.byId(itemId).map(McItem::zh).orElse(itemId);
    }

    private String userIdString(Long userId) {
        return userId == null ? null : String.valueOf(userId);
    }
}
