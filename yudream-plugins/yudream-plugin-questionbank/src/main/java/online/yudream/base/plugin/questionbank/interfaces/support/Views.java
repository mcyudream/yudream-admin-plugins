package online.yudream.base.plugin.questionbank.interfaces.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.questionbank.application.AdminRecordService;
import online.yudream.base.plugin.questionbank.domain.Paper;
import online.yudream.base.plugin.questionbank.domain.PracticeSession;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.domain.SessionAnswer;
import online.yudream.base.plugin.questionbank.domain.SessionQuestion;

/** 响应装配：题目视图、会话摘要/详情。未结束的会话必须剥离答案与解析（reveal=false）。 */
public final class Views {
    private Views() {
    }

    public static Map<String, Object> questionView(Question question, String categoryName) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", question.id());
        view.put("type", question.type().name());
        view.put("typeLabel", question.type().label());
        view.put("categoryId", question.categoryId());
        view.put("categoryName", categoryName);
        view.put("tags", question.tags() == null ? List.of() : question.tags());
        view.put("content", question.content());
        view.put("options", question.options() == null ? List.of() : question.options());
        view.put("answer", question.answer());
        view.put("answers", question.answers() == null ? List.of() : question.answers());
        view.put("blanks", question.blanks() == null ? List.of() : question.blanks());
        view.put("referenceAnswer", question.referenceAnswer());
        view.put("analysis", question.analysis());
        view.put("difficulty", question.difficulty());
        view.put("status", question.status());
        view.put("createdBy", question.createdBy());
        view.put("createdByName", question.createdByName());
        view.put("createdAt", question.createdAt());
        view.put("updatedAt", question.updatedAt());
        return view;
    }

    /** 会话摘要（列表用，不含题目）。includeUser 仅供管理端视图使用。 */
    public static Map<String, Object> sessionSummary(PracticeSession session, boolean includeUser) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", session.id());
        if (includeUser) {
            view.put("userId", session.userId());
            view.put("userName", session.userName());
        }
        view.put("status", session.status());
        view.put("totalCount", session.totalCount());
        view.put("correctCount", session.correctCount());
        view.put("requestedCount", session.requestedCount());
        view.put("categoryId", session.categoryId());
        view.put("tags", session.tags() == null ? List.of() : session.tags());
        view.put("types", session.types() == null ? List.of() : session.types());
        view.put("difficulties", session.difficulties() == null ? List.of() : session.difficulties());
        view.put("createdAt", session.createdAt());
        view.put("submittedAt", session.submittedAt());
        view.put("paperId", session.paperId());
        view.put("paperName", session.paperName());
        view.put("subjectiveMode", session.subjectiveMode());
        view.put("pendingReview", AdminRecordService.needsReview(session));
        return view;
    }

    public static Map<String, Object> paperView(Paper paper) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", paper.id());
        view.put("name", paper.name());
        view.put("description", paper.description());
        view.put("mode", paper.mode());
        view.put("categoryId", paper.categoryId());
        view.put("tags", paper.tags() == null ? List.of() : paper.tags());
        view.put("types", paper.types() == null ? List.of() : paper.types());
        view.put("difficulties", paper.difficulties() == null ? List.of() : paper.difficulties());
        view.put("count", paper.count());
        view.put("questionIds", paper.questionIds() == null ? List.of() : paper.questionIds());
        view.put("questionCount", paper.ruleMode()
                ? paper.count()
                : (paper.questionIds() == null ? 0 : paper.questionIds().size()));
        view.put("subjectiveMode", paper.subjectiveMode());
        view.put("status", paper.status());
        view.put("createdBy", paper.createdBy());
        view.put("createdByName", paper.createdByName());
        view.put("createdAt", paper.createdAt());
        view.put("updatedAt", paper.updatedAt());
        return view;
    }

    /** 打印视图：题单信息 + 现场抽取的题目（含答案，供管理端打印/导出）。 */
    public static Map<String, Object> printView(Paper paper, List<Question> drawn, String categoryName) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("paper", paperView(paper));
        view.put("categoryName", categoryName);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Question question : drawn) {
            items.add(questionView(question, categoryName));
        }
        view.put("questions", items);
        view.put("drawnAt", System.currentTimeMillis());
        return view;
    }

    /**
     * 会话详情。reveal=false（进行中）时剥离答案/解析，仅保留 blankCount 供渲染填空输入框。
     */
    public static Map<String, Object> sessionView(PracticeSession session, boolean reveal, boolean includeUser) {
        Map<String, Object> view = sessionSummary(session, includeUser);
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, SessionAnswer> answerByQuestion = new LinkedHashMap<>();
        for (SessionAnswer answer : session.answers() == null ? List.<SessionAnswer>of() : session.answers()) {
            answerByQuestion.put(answer.questionId(), answer);
        }
        for (SessionQuestion question : session.questions() == null ? List.<SessionQuestion>of() : session.questions()) {
            items.add(sessionQuestionView(question, answerByQuestion.get(question.questionId()), reveal));
        }
        view.put("questions", items);
        return view;
    }

    private static Map<String, Object> sessionQuestionView(SessionQuestion question, SessionAnswer answer, boolean reveal) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("questionId", question.questionId());
        view.put("type", question.type());
        view.put("typeLabel", question.questionType().label());
        view.put("content", question.content());
        view.put("options", question.options() == null ? List.of() : question.options());
        view.put("blankCount", question.blanks() == null ? 0 : question.blanks().size());
        view.put("difficulty", question.difficulty());
        if (reveal) {
            view.put("answer", question.answer());
            view.put("answers", question.answers() == null ? List.of() : question.answers());
            view.put("blanks", question.blanks() == null ? List.of() : question.blanks());
            view.put("referenceAnswer", question.referenceAnswer());
            view.put("analysis", question.analysis());
        }
        if (answer != null) {
            Map<String, Object> userAnswer = new LinkedHashMap<>();
            userAnswer.put("choice", answer.choice());
            userAnswer.put("choices", answer.choices() == null ? List.of() : answer.choices());
            userAnswer.put("blanks", answer.blanks() == null ? List.of() : answer.blanks());
            userAnswer.put("text", answer.text());
            userAnswer.put("correct", answer.correct());
            view.put("userAnswer", userAnswer);
        }
        return view;
    }
}
