package online.yudream.base.plugin.questionbank.interfaces;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.questionbank.application.CategoryService;
import online.yudream.base.plugin.questionbank.application.ComposeRecordService;
import online.yudream.base.plugin.questionbank.application.ComposeService;
import online.yudream.base.plugin.questionbank.application.PageResult;
import online.yudream.base.plugin.questionbank.application.QuestionService;
import online.yudream.base.plugin.questionbank.bootstrap.QuestionBankPlugin;
import online.yudream.base.plugin.questionbank.domain.ComposeRecord;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.document.PluginRenderedDocument;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;

/**
 * 组卷记录接口（/admin/compose/records/**）。
 * COMPOSE 用户保存并管理自己的组卷记录；带 MANAGE 权限的用户可查全部记录并代为管理。
 * 归属一律取 principal.userId()，不接受请求参数指定数据所有者。
 */
public final class QuestionBankComposeRecordController {
    private final ComposeRecordService recordService;
    private final ComposeService composeService;
    private final QuestionService questionService;
    private final CategoryService categoryService;
    private final JsonSupport json;

    public QuestionBankComposeRecordController(ComposeRecordService recordService, ComposeService composeService,
                                               QuestionService questionService, CategoryService categoryService,
                                               JsonSupport json) {
        this.recordService = recordService;
        this.composeService = composeService;
        this.questionService = questionService;
        this.categoryService = categoryService;
        this.json = json;
    }

    /** 保存当前组卷为记录。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/compose/records", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse save(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            SaveRecordRequest body = json.read(request.body(), SaveRecordRequest.class);
            if (body == null) {
                throw new IllegalArgumentException("保存参数不能为空");
            }
            String userId = HttpSupport.requireUserId(request);
            ComposeRecord record = recordService.save(userId, questionService.resolveUserName(userId),
                    body.title(), body.description(), Boolean.TRUE.equals(body.withAnswers()), body.questionIds());
            return PluginHttpResponse.ok(view(record));
        });
    }

    /** 记录列表：默认只看自己的；具备 MANAGE 权限时 all=true 可查全部（可按 ownerId/keyword 过滤）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/compose/records", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse list(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            boolean manage = manage(request);
            int page = HttpSupport.pageParam(request);
            int size = HttpSupport.sizeParam(request, 20);
            String keyword = HttpSupport.first(request, "keyword");
            PageResult<ComposeRecord> result = manage && "true".equalsIgnoreCase(HttpSupport.first(request, "all"))
                    ? recordService.listAll(keyword, HttpSupport.first(request, "ownerId"), page, size)
                    : recordService.listMine(userId, keyword, page, size);
            return PluginHttpResponse.ok(Map.of(
                    "records", result.records().stream().map(this::view).toList(),
                    "total", result.total()));
        });
    }

    /** 记录详情（含解析后的题目，带答案解析，供在线查看）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/compose/records/{id}", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            ComposeRecord record = recordService.requireAccessible(recordId(request), userId, manage(request));
            Map<String, Object> view = new LinkedHashMap<>(view(record));
            view.put("questions", questionViews(record));
            return PluginHttpResponse.ok(view);
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/compose/records/{id}", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            recordService.delete(recordId(request), userId, manage(request));
            return PluginHttpResponse.ok(Map.of("deleted", Boolean.TRUE));
        });
    }

    /** 从记录再次导出 Word（题目实时解析，缺失题目自动跳过）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/compose/records/{id}/export-word", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse exportWord(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            ComposeRecord record = recordService.requireAccessible(recordId(request), userId, manage(request));
            List<Question> questions = composeService.byIds(record.questionIds());
            PluginRenderedDocument document = composeService.exportWord(
                    record.title(), record.description(), questions, record.withAnswers());
            return HttpSupport.download(record.title() + ".docx", document.contentType(), document.content());
        });
    }

    /** 开启分享：返回公开 token（前端拼公开页链接）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/compose/records/{id}/share", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse share(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            ComposeRecord record = recordService.share(recordId(request), userId, manage(request));
            return PluginHttpResponse.ok(Map.of("token", record.shareToken()));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/compose/records/{id}/unshare", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse unshare(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String userId = HttpSupport.requireUserId(request);
            recordService.unshare(recordId(request), userId, manage(request));
            return PluginHttpResponse.ok(Map.of("shared", Boolean.FALSE));
        });
    }

    private String recordId(PluginHttpRequest request) {
        return HttpSupport.segmentAfter(request.path(), "records");
    }

    private boolean manage(PluginHttpRequest request) {
        PluginPrincipal principal = request.principal();
        return principal != null && principal.hasPermission(QuestionBankPlugin.MANAGE_PERMISSION);
    }

    private List<Map<String, Object>> questionViews(ComposeRecord record) {
        return composeService.byIds(record.questionIds()).stream()
                .map(question -> online.yudream.base.plugin.questionbank.interfaces.support.Views
                        .questionView(question, categoryService.categoryName(question.categoryId())))
                .toList();
    }

    private Map<String, Object> view(ComposeRecord record) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", record.id());
        view.put("title", record.title());
        view.put("description", record.description());
        view.put("withAnswers", record.withAnswers());
        view.put("questionCount", record.questionIds().size());
        view.put("ownerId", record.ownerId());
        view.put("ownerName", record.ownerName());
        view.put("shared", record.shared());
        view.put("shareToken", record.shareToken());
        view.put("shareAt", record.shareAt());
        view.put("createdAt", record.createdAt());
        view.put("updatedAt", record.updatedAt());
        return view;
    }

    /** 保存组卷记录负载。 */
    public record SaveRecordRequest(String title, String description, Boolean withAnswers, List<String> questionIds) {
    }
}
