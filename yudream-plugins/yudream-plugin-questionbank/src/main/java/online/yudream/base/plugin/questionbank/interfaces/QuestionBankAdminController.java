package online.yudream.base.plugin.questionbank.interfaces;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.questionbank.application.AdminRecordService;
import online.yudream.base.plugin.questionbank.application.AiImportJobService;
import online.yudream.base.plugin.questionbank.application.AiImportService;
import online.yudream.base.plugin.questionbank.application.CategoryService;
import online.yudream.base.plugin.questionbank.application.PageResult;
import online.yudream.base.plugin.questionbank.application.PaperPayload;
import online.yudream.base.plugin.questionbank.application.PaperService;
import online.yudream.base.plugin.questionbank.application.QuestionPayload;
import online.yudream.base.plugin.questionbank.application.QuestionQuery;
import online.yudream.base.plugin.questionbank.application.QuestionService;
import online.yudream.base.plugin.questionbank.application.SettingsService;
import online.yudream.base.plugin.questionbank.bootstrap.QuestionBankPlugin;
import online.yudream.base.plugin.questionbank.domain.Paper;
import online.yudream.base.plugin.questionbank.domain.PracticeSession;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.interfaces.request.AiImportRequest;
import online.yudream.base.plugin.questionbank.interfaces.request.BatchDeleteRequest;
import online.yudream.base.plugin.questionbank.interfaces.request.CategoryMergeRequest;
import online.yudream.base.plugin.questionbank.interfaces.request.CategoryPayload;
import online.yudream.base.plugin.questionbank.interfaces.request.ImportRequest;
import online.yudream.base.plugin.questionbank.interfaces.request.SelfMarkRequest;
import online.yudream.base.plugin.questionbank.interfaces.request.SettingsRequest;
import online.yudream.base.plugin.questionbank.interfaces.support.HttpSupport;
import online.yudream.base.plugin.questionbank.interfaces.support.Views;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.http.PluginSseStream;

/**
 * 管理端题库接口（/admin/**）：跨用户题目维护、分类标签、导入导出、题单、审核与练习记录。
 * 全部端点要求 MANAGE 权限；与用户端 /me 数据范围完全分离。
 */
public final class QuestionBankAdminController {
    private final QuestionService questionService;
    private final CategoryService categoryService;
    private final AdminRecordService recordService;
    private final PaperService paperService;
    private final SettingsService settingsService;
    private final AiImportService aiImportService;
    private final AiImportJobService aiImportJobService;
    private final JsonSupport json;

    public QuestionBankAdminController(QuestionService questionService, CategoryService categoryService,
                                       AdminRecordService recordService, PaperService paperService,
                                       SettingsService settingsService, AiImportService aiImportService,
                                       AiImportJobService aiImportJobService, JsonSupport json) {
        this.questionService = questionService;
        this.categoryService = categoryService;
        this.recordService = recordService;
        this.paperService = paperService;
        this.settingsService = settingsService;
        this.aiImportService = aiImportService;
        this.aiImportJobService = aiImportJobService;
        this.json = json;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/questions", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            QuestionQuery query = buildQuery(request);
            PageResult<Question> result = questionService.query(query);
            return PluginHttpResponse.ok(Map.of(
                    "records", result.records().stream()
                            .map(question -> Views.questionView(question, categoryService.categoryName(question.categoryId())))
                            .toList(),
                    "total", result.total()));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/questions", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            QuestionPayload payload = json.read(request.body(), QuestionPayload.class);
            Question created = questionService.create(payload, userId, questionService.resolveUserName(userId));
            return PluginHttpResponse.ok(Views.questionView(created, categoryService.categoryName(created.categoryId())));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/questions/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            Question question = questionService.require(HttpSupport.segmentAfter(request.path(), "questions"));
            return PluginHttpResponse.ok(Views.questionView(question, categoryService.categoryName(question.categoryId())));
        });
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/questions/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse update(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            QuestionPayload payload = json.read(request.body(), QuestionPayload.class);
            Question updated = questionService.update(HttpSupport.segmentAfter(request.path(), "questions"), payload);
            return PluginHttpResponse.ok(Views.questionView(updated, categoryService.categoryName(updated.categoryId())));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/questions/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            questionService.delete(HttpSupport.segmentAfter(request.path(), "questions"));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/questions/batch-delete", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse batchDelete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            BatchDeleteRequest body = json.read(request.body(), BatchDeleteRequest.class);
            int deleted = questionService.batchDelete(body.ids());
            return PluginHttpResponse.ok(Map.of("deleted", deleted));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/questions-export", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse export(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            QuestionQuery query = buildQuery(request);
            String format = HttpSupport.first(request, "format");
            if ("markdown".equalsIgnoreCase(format)) {
                String markdown = questionService.exportMarkdown(query);
                return HttpSupport.download("题库导出.md", "text/markdown; charset=utf-8",
                        markdown.getBytes(StandardCharsets.UTF_8));
            }
            String jsonText = questionService.exportJson(query);
            // 宿主前端 blob 下载把 application/json 响应当错误信封处理，这里用 octet-stream 绕过
            return HttpSupport.download("题库导出.json", "application/octet-stream",
                    jsonText.getBytes(StandardCharsets.UTF_8));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/questions/import", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse importQuestions(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            ImportRequest body = json.read(request.body(), ImportRequest.class);
            QuestionService.ImportResult result = questionService.importQuestions(
                    body.questions(), userId, questionService.resolveUserName(userId));
            return PluginHttpResponse.ok(Map.of(
                    "imported", result.imported(),
                    "failures", result.failures()));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/questions/ai-import/start", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse aiImportStart(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            AiImportRequest body = json.read(request.body(), AiImportRequest.class);
            String jobId = aiImportJobService.start(body.text(), userId, questionService.resolveUserName(userId));
            return PluginHttpResponse.ok(Map.of("jobId", jobId));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/questions/ai-import/{jobId}/events", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse aiImportEvents(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String jobId = HttpSupport.segmentAfter(request.path(), "ai-import");
            PluginSseStream stream = aiImportJobService.stream(jobId);
            if (stream == null) {
                return PluginHttpResponse.rawJson(404, Map.of("message", "导入任务不存在或已被清理"));
            }
            return new PluginHttpResponse(200,
                    Map.of("Cache-Control", "no-cache", "Connection", "keep-alive"),
                    "text/event-stream", stream, false);
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/ai-options", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse aiOptions(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records", aiImportService.providerOptions())));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/categories", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse categories(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records", categoryService.listWithCounts())));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/categories", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createCategory(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CategoryPayload payload = json.read(request.body(), CategoryPayload.class);
            return PluginHttpResponse.ok(categoryService.create(payload.name(), payload.sort()));
        });
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/categories/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updateCategory(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CategoryPayload payload = json.read(request.body(), CategoryPayload.class);
            return PluginHttpResponse.ok(categoryService.update(
                    HttpSupport.segmentAfter(request.path(), "categories"), payload.name(), payload.sort()));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/categories/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteCategory(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            categoryService.delete(HttpSupport.segmentAfter(request.path(), "categories"));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/categories/merge", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse mergeCategories(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            CategoryMergeRequest payload = json.read(request.body(), CategoryMergeRequest.class);
            int moved = categoryService.merge(payload.targetId(),
                    payload.sourceIds() == null ? List.of() : payload.sourceIds());
            return PluginHttpResponse.ok(Map.of("moved", moved));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/tags", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse tags(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of("records", questionService.listTags())));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/records", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse records(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            PageResult<PracticeSession> result = recordService.list(
                    HttpSupport.first(request, "keyword"), HttpSupport.first(request, "status"),
                    HttpSupport.pageParam(request), HttpSupport.sizeParam(request, 20));
            return PluginHttpResponse.ok(Map.of(
                    "records", result.records().stream().map(session -> Views.sessionSummary(session, true)).toList(),
                    "total", result.total()));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/records/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse recordDetail(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Views.sessionView(
                recordService.require(HttpSupport.segmentAfter(request.path(), "records")), true, true)));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/records/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteRecord(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            recordService.delete(HttpSupport.segmentAfter(request.path(), "records"));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/papers", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse papers(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            PageResult<Paper> result = paperService.list(
                    HttpSupport.first(request, "keyword"), HttpSupport.first(request, "status"),
                    HttpSupport.pageParam(request), HttpSupport.sizeParam(request, 20));
            return PluginHttpResponse.ok(Map.of(
                    "records", result.records().stream().map(Views::paperView).toList(),
                    "total", result.total()));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/papers", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createPaper(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            PaperPayload payload = json.read(request.body(), PaperPayload.class);
            Paper paper = paperService.create(userId, questionService.resolveUserName(userId), payload);
            return PluginHttpResponse.ok(Views.paperView(paper));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/papers/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse paperDetail(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(
                Views.paperView(paperService.require(HttpSupport.segmentAfter(request.path(), "papers")))));
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/papers/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updatePaper(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            PaperPayload payload = json.read(request.body(), PaperPayload.class);
            Paper paper = paperService.update(HttpSupport.segmentAfter(request.path(), "papers"), payload);
            return PluginHttpResponse.ok(Views.paperView(paper));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/papers/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deletePaper(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            paperService.delete(HttpSupport.segmentAfter(request.path(), "papers"));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    /** 打印数据：RULE 模式每次调用现场重新随机抽题；MANUAL 模式固定选题（跳过已删/停用）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/papers/{id}/print", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse printPaper(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            Paper paper = paperService.require(HttpSupport.segmentAfter(request.path(), "papers"));
            List<Question> drawn = paperService.draw(paper);
            String categoryName = paper.ruleMode() ? categoryService.categoryName(paper.categoryId()) : null;
            return PluginHttpResponse.ok(Views.printView(paper, drawn, categoryName));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/review", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse reviewQueue(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            PageResult<PracticeSession> result = recordService.pendingReview(
                    HttpSupport.first(request, "keyword"),
                    HttpSupport.pageParam(request), HttpSupport.sizeParam(request, 20));
            return PluginHttpResponse.ok(Map.of(
                    "records", result.records().stream().map(session -> Views.sessionSummary(session, true)).toList(),
                    "total", result.total()));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/review/{id}", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse review(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            SelfMarkRequest body = json.read(request.body(), SelfMarkRequest.class);
            if (body.questionId() == null || body.questionId().isBlank() || body.correct() == null) {
                throw new IllegalArgumentException("questionId 与 correct 不能为空");
            }
            PracticeSession session = recordService.review(
                    HttpSupport.segmentAfter(request.path(), "review"), body.questionId(), body.correct());
            return PluginHttpResponse.ok(Views.sessionView(session, true, true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settings(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(settingsService.settingsView()));
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = QuestionBankPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updateSettings(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            SettingsRequest body = json.read(request.body(), SettingsRequest.class);
            settingsService.update(body.practiceEnabled(), body.aiProviderCode(), body.aiModelCode(),
                    body.qqGroups(), body.qqDefaultGroup(), body.qqAnswerSeconds(), body.qqAiGrading());
            return PluginHttpResponse.ok(settingsService.settingsView());
        });
    }

    private QuestionQuery buildQuery(PluginHttpRequest request) {
        Integer difficulty = null;
        String rawDifficulty = HttpSupport.first(request, "difficulty");
        if (!rawDifficulty.isBlank()) {
            try {
                difficulty = Integer.parseInt(rawDifficulty.trim());
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("difficulty 必须是数字");
            }
        }
        List<String> ids = Arrays.stream(HttpSupport.first(request, "ids").split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList();
        return new QuestionQuery(
                HttpSupport.first(request, "keyword"),
                HttpSupport.first(request, "categoryId"),
                HttpSupport.first(request, "tag"),
                HttpSupport.first(request, "type"),
                difficulty,
                HttpSupport.first(request, "status"),
                HttpSupport.pageParam(request),
                HttpSupport.sizeParam(request, 20),
                ids);
    }
}
