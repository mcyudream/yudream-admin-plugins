package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import online.yudream.base.plugin.questionbank.domain.PracticeSession;
import online.yudream.base.plugin.questionbank.domain.QuestionType;
import online.yudream.base.plugin.questionbank.domain.SessionAnswer;
import online.yudream.base.plugin.questionbank.infrastructure.SessionRepository;

/** 管理端跨用户练习记录：分页列表、详情、删除、简答人工审核。独立用例，与用户端 /me 完全隔离。 */
public final class AdminRecordService {
    private final SessionRepository sessions;

    public AdminRecordService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    public PageResult<PracticeSession> list(String keyword, String status, int page, int size) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<PracticeSession> filtered = sessions.listAll().stream()
                .filter(session -> status == null || status.isBlank()
                        || session.status().equalsIgnoreCase(status))
                .filter(session -> normalizedKeyword.isEmpty()
                        || (session.userName() != null
                                && session.userName().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                        || session.userId().contains(normalizedKeyword))
                .toList();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageResult.of(filtered.subList(from, to), filtered.size());
    }

    /** 人工审核队列：已提交、REVIEW 模式（或 AI 判分失败回落）且仍有简答题未判分的会话。 */
    public PageResult<PracticeSession> pendingReview(String keyword, int page, int size) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<PracticeSession> filtered = sessions.listAll().stream()
                .filter(AdminRecordService::needsReview)
                .filter(session -> normalizedKeyword.isEmpty()
                        || (session.userName() != null
                                && session.userName().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                        || session.userId().contains(normalizedKeyword)
                        || (session.paperName() != null
                                && session.paperName().toLowerCase(Locale.ROOT).contains(normalizedKeyword)))
                .toList();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageResult.of(filtered.subList(from, to), filtered.size());
    }

    public static boolean needsReview(PracticeSession session) {
        if (!PracticeSession.STATUS_FINISHED.equals(session.status())
                || (!session.reviewMode() && !session.aiMode())) {
            return false;
        }
        return session.questions().stream()
                .filter(question -> question.questionType() == QuestionType.SHORT)
                .anyMatch(question -> session.answers().stream()
                        .filter(answer -> answer.questionId().equals(question.questionId()))
                        .anyMatch(answer -> answer.correct() == null));
    }

    /** 人工审核：对已提交会话中的某道简答题判定对/错并重算正确数。 */
    public PracticeSession review(String sessionId, String questionId, boolean correct) {
        PracticeSession session = require(sessionId);
        if (!session.reviewMode() && !session.aiMode()) {
            throw new IllegalArgumentException("该记录不是人工审核模式，无需审核");
        }
        if (session.ongoing()) {
            throw new IllegalArgumentException("作答尚未提交，无法审核");
        }
        boolean isShort = session.questions().stream()
                .anyMatch(question -> question.questionId().equals(questionId)
                        && question.questionType() == QuestionType.SHORT);
        if (!isShort) {
            throw new IllegalArgumentException("只有简答题需要人工审核");
        }
        List<SessionAnswer> answers = new ArrayList<>();
        int correctCount = 0;
        for (SessionAnswer answer : session.answers()) {
            SessionAnswer next = answer.questionId().equals(questionId)
                    ? new SessionAnswer(answer.questionId(), answer.choice(), answer.choices(), answer.blanks(),
                            answer.text(), correct)
                    : answer;
            answers.add(next);
            if (Boolean.TRUE.equals(next.correct())) {
                correctCount++;
            }
        }
        PracticeSession updated = new PracticeSession(
                session.id(), session.userId(), session.userName(), session.categoryId(), session.tags(),
                session.types(), session.difficulties(), session.requestedCount(), session.questions(),
                List.copyOf(answers), session.status(), correctCount, session.createdAt(), session.submittedAt(),
                session.paperId(), session.paperName(), session.subjectiveMode()
        );
        sessions.save(updated);
        return updated;
    }

    public PracticeSession require(String sessionId) {
        return sessions.findById(sessionId).orElseThrow(() -> new NotFoundException("练习记录不存在"));
    }

    public void delete(String sessionId) {
        require(sessionId);
        sessions.delete(sessionId);
    }
}
