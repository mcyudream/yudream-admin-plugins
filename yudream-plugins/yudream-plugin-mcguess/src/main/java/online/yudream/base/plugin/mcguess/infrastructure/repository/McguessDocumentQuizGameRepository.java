package online.yudream.base.plugin.mcguess.infrastructure.repository;

import online.yudream.base.plugin.mcguess.domain.QuizGame;
import online.yudream.base.plugin.mcguess.domain.QuizGame.Question;
import online.yudream.base.plugin.mcguess.domain.QuizGame.QuizGuess;
import online.yudream.base.plugin.mcguess.domain.QuizGameRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class McguessDocumentQuizGameRepository extends DocumentChannelGameRepository<QuizGame> implements QuizGameRepository {

    private static final String COLLECTION = "mcguess_quiz_games";

    public McguessDocumentQuizGameRepository(PluginDocumentStore store) {
        super(store, COLLECTION);
    }

    @Override
    protected Map<String, Object> toDoc(QuizGame game) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", game.getId());
        doc.put("mode", "quiz");
        doc.put("connectionId", game.getConnectionId());
        doc.put("platform", game.getPlatform());
        doc.put("channelId", game.getChannelId());
        doc.put("channelKey", game.getChannelKey());
        doc.put("status", game.getStatus());
        doc.put("startedByQq", game.getStartedByQq());
        doc.put("startedByUserId", game.getStartedByUserId());
        doc.put("winnerQq", game.getWinnerQq());
        doc.put("winnerUserId", game.getWinnerUserId());
        doc.put("startedAt", game.getStartedAt());
        doc.put("endedAt", game.getEndedAt());
        List<Map<String, Object>> questionDocs = new ArrayList<>();
        for (int i = 0; i < game.questionCount(); i++) {
            Question question = game.getQuestions().get(i);
            Map<String, Object> questionDoc = new HashMap<>();
            questionDoc.put("targetId", question.targetId());
            questionDoc.put("ingredientId", question.ingredientId());
            questionDoc.put("answer", question.answer());
            questionDoc.put("choices", new ArrayList<>(question.choices()));
            questionDoc.put("correctQq", game.correctQqOf(i));
            questionDoc.put("correctUserId", game.correctUserIdOf(i));
            questionDoc.put("wrong", new ArrayList<>(game.wrongUsersOf(i)));
            DocumentSupport.stripNulls(questionDoc);
            questionDocs.add(questionDoc);
        }
        doc.put("questions", questionDocs);
        List<Map<String, Object>> guessDocs = new ArrayList<>();
        for (QuizGuess guess : game.getGuesses()) {
            Map<String, Object> guessDoc = new HashMap<>();
            guessDoc.put("question", guess.question());
            guessDoc.put("choice", guess.choice());
            guessDoc.put("correct", guess.correct());
            guessDoc.put("qq", guess.qq());
            guessDoc.put("userId", guess.userId());
            guessDoc.put("at", guess.at());
            DocumentSupport.stripNulls(guessDoc);
            guessDocs.add(guessDoc);
        }
        doc.put("guesses", guessDocs);
        return doc;
    }

    @Override
    protected QuizGame toGame(Map<String, Object> doc) {
        List<Question> questions = new ArrayList<>();
        for (Map<String, Object> questionDoc : DocumentSupport.mapList(doc, "questions")) {
            List<Integer> choices = new ArrayList<>();
            for (String value : DocumentSupport.stringList(questionDoc, "choices")) {
                choices.add(Integer.parseInt(value));
            }
            questions.add(new Question(
                    DocumentSupport.string(questionDoc, "targetId"),
                    DocumentSupport.string(questionDoc, "ingredientId"),
                    DocumentSupport.integer(questionDoc, "answer", 0),
                    choices));
        }
        QuizGame game = new QuizGame(
                DocumentSupport.string(doc, "id"),
                DocumentSupport.string(doc, "connectionId"),
                DocumentSupport.string(doc, "platform"),
                DocumentSupport.string(doc, "channelId"),
                questions,
                DocumentSupport.string(doc, "startedByQq"),
                DocumentSupport.string(doc, "startedByUserId"),
                DocumentSupport.longValue(doc, "startedAt", 0));
        int index = 0;
        for (Map<String, Object> questionDoc : DocumentSupport.mapList(doc, "questions")) {
            game.restoreQuestionState(index,
                    DocumentSupport.string(questionDoc, "correctQq"),
                    DocumentSupport.string(questionDoc, "correctUserId"),
                    new LinkedHashSet<>(DocumentSupport.stringList(questionDoc, "wrong")));
            index++;
        }
        for (Map<String, Object> guessDoc : DocumentSupport.mapList(doc, "guesses")) {
            game.addGuess(new QuizGuess(
                    DocumentSupport.integer(guessDoc, "question", 0),
                    DocumentSupport.integer(guessDoc, "choice", 0),
                    DocumentSupport.bool(guessDoc, "correct", false),
                    DocumentSupport.string(guessDoc, "qq"),
                    DocumentSupport.string(guessDoc, "userId"),
                    DocumentSupport.longValue(guessDoc, "at", 0)));
        }
        String status = DocumentSupport.stringOrDefault(doc, "status", QuizGame.STATUS_PLAYING);
        if (QuizGame.STATUS_WON.equals(status)) {
            game.win(DocumentSupport.string(doc, "winnerQq"), DocumentSupport.string(doc, "winnerUserId"),
                    DocumentSupport.longValue(doc, "endedAt", 0));
        } else if (QuizGame.STATUS_LOST.equals(status)) {
            game.lose(DocumentSupport.longValue(doc, "endedAt", 0));
        }
        return game;
    }
}
