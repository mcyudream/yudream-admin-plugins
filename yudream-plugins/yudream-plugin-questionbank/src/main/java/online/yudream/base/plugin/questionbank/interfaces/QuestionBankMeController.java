package online.yudream.base.plugin.questionbank.interfaces;

import java.util.Map;
import online.yudream.base.plugin.questionbank.application.AnswerPayload;
import online.yudream.base.plugin.questionbank.application.PageResult;
import online.yudream.base.plugin.questionbank.application.PaperService;
import online.yudream.base.plugin.questionbank.application.PracticeFilter;
import online.yudream.base.plugin.questionbank.application.PracticeService;
import online.yudream.base.plugin.questionbank.application.QuizScoreService;
import online.yudream.base.plugin.questionbank.bootstrap.QuestionBankPlugin;
import online.yudream.base.plugin.questionbank.domain.PracticeSession;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.interfaces.request.SelfMarkRequest;
import online.yudream.base.plugin.questionbank.interfaces.request.SubmitRequest;
import online.yudream.base.plugin.questionbank.interfaces.support.HttpSupport;
import online.yudream.base.plugin.questionbank.interfaces.support.Views;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 用户端题库接口（/me/**）：只访问当前登录用户自己的练习数据。
 * 归属一律取 principal.userId()，禁止从请求参数选择数据所有者；管理权限在此不扩大数据范围。
 */
public final class QuestionBankMeController {
    private final PracticeService practiceService;
    private final PaperService paperService;
    private final QuizScoreService quizScoreService;
    private final JsonSupport json;

    public QuestionBankMeController(PracticeService practiceService, PaperService paperService,
                                    QuizScoreService quizScoreService, JsonSupport json) {
        this.practiceService = practiceService;
        this.paperService = paperService;
        this.quizScoreService = quizScoreService;
        this.json = json;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/meta", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse meta(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(practiceService.meta()));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/practice/count", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse count(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            PracticeFilter filter = json.read(request.body(), PracticeFilter.class);
            return PluginHttpResponse.ok(Map.of("count", practiceService.countPool(filter)));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/practice/sessions", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            PracticeFilter filter = json.read(request.body(), PracticeFilter.class);
            PracticeSession session = practiceService.createSession(userId, filter);
            return PluginHttpResponse.ok(Views.sessionView(session, false, false));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/practice/sessions", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse listMine(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            PageResult<PracticeSession> result = practiceService.listMine(
                    userId, HttpSupport.pageParam(request), HttpSupport.sizeParam(request, 20));
            return PluginHttpResponse.ok(Map.of(
                    "records", result.records().stream().map(session -> Views.sessionSummary(session, false)).toList(),
                    "total", result.total()));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/practice/sessions/{id}", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            PracticeSession session = practiceService.requireMine(userId, HttpSupport.segmentAfter(request.path(), "sessions"));
            return PluginHttpResponse.ok(Views.sessionView(session, !session.ongoing(), false));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/practice/sessions/{id}/submit", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse submit(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            SubmitRequest body = json.read(request.body(), SubmitRequest.class);
            PracticeSession session = practiceService.submit(userId,
                    HttpSupport.segmentAfter(request.path(), "sessions"),
                    body.answers() == null ? java.util.List.<AnswerPayload>of() : body.answers());
            return PluginHttpResponse.ok(Views.sessionView(session, true, false));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/practice/sessions/{id}/self-mark", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse selfMark(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            SelfMarkRequest body = json.read(request.body(), SelfMarkRequest.class);
            if (body.questionId() == null || body.questionId().isBlank() || body.correct() == null) {
                throw new IllegalArgumentException("questionId 与 correct 不能为空");
            }
            PracticeSession session = practiceService.selfMark(userId,
                    HttpSupport.segmentAfter(request.path(), "sessions"), body.questionId(), body.correct());
            return PluginHttpResponse.ok(Views.sessionView(session, true, false));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/practice/sessions/{id}", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse deleteMine(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            practiceService.deleteMine(userId, HttpSupport.segmentAfter(request.path(), "sessions"));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/papers", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse papers(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "records", paperService.listPublished().stream().map(Views::paperView).toList())));
    }

    /**
     * QQ 抢答排行榜（聚合数据，所有登录用户可见）。
     * 归属绑定在服务端按 QQ 反解，响应只含展示名与题数，不含原始 QQ 号。
     */
    @PluginHttpEndpoint(method = "GET", path = "/me/quiz/leaderboard", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse quizLeaderboard(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records", quizScoreService.leaderboard())));
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/papers/{id}/attempt", permission = QuestionBankPlugin.VIEW_PERMISSION)
    public PluginHttpResponse attemptPaper(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            PracticeSession session = practiceService.attemptPaper(userId,
                    HttpSupport.segmentAfter(request.path(), "papers"));
            return PluginHttpResponse.ok(Views.sessionView(session, false, false));
        });
    }
}
