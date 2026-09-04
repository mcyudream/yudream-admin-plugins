package online.yudream.base.plugin.questionbank.interfaces;

import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.questionbank.application.CategoryService;
import online.yudream.base.plugin.questionbank.application.ComposeService;
import online.yudream.base.plugin.questionbank.application.PageResult;
import online.yudream.base.plugin.questionbank.application.QuestionQuery;
import online.yudream.base.plugin.questionbank.application.QuestionService;
import online.yudream.base.plugin.questionbank.bootstrap.QuestionBankPlugin;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.interfaces.request.ComposeDrawRequest;
import online.yudream.base.plugin.questionbank.interfaces.request.ComposeExportRequest;
import online.yudream.base.plugin.questionbank.interfaces.support.HttpSupport;
import online.yudream.base.plugin.questionbank.interfaces.support.Views;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.document.PluginRenderedDocument;

/**
 * 组卷中心接口（/admin/compose/**）：面向 COMPOSE 中间权限用户。
 * 只读题目列表、随机抽题、Word 导出（即组即下载，不持久化）；
 * 不提供题目/分类/设置等维护能力，与 MANAGE 数据面完全分离。
 */
public final class QuestionBankComposeController {
    private final QuestionService questionService;
    private final CategoryService categoryService;
    private final ComposeService composeService;
    private final JsonSupport json;

    public QuestionBankComposeController(QuestionService questionService, CategoryService categoryService,
                                         ComposeService composeService, JsonSupport json) {
        this.questionService = questionService;
        this.categoryService = categoryService;
        this.composeService = composeService;
        this.json = json;
    }

    /** 手选拾取器：启用题分页列表（含答案，供组卷人预览）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/compose/questions", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse questions(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
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
            QuestionQuery query = new QuestionQuery(
                    HttpSupport.first(request, "keyword"),
                    HttpSupport.first(request, "categoryId"),
                    HttpSupport.first(request, "tag"),
                    HttpSupport.first(request, "type"),
                    difficulty,
                    Question.STATUS_ENABLED,
                    HttpSupport.pageParam(request),
                    HttpSupport.sizeParam(request, 20),
                    List.of());
            PageResult<Question> result = questionService.query(query);
            return PluginHttpResponse.ok(Map.of(
                    "records", result.records().stream()
                            .map(question -> Views.questionView(question, categoryService.categoryName(question.categoryId())))
                            .toList(),
                    "total", result.total()));
        });
    }

    /** 拾取器筛选项：分类（带题目数）+ 全库标签。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/compose/options", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse options(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "categories", categoryService.listWithCounts(),
                "tags", questionService.listTags())));
    }

    /** 随机抽题预览：返回带答案的题目视图（组卷人可自行决定是否附答案导出）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/compose/draw", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse draw(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            ComposeDrawRequest body = json.read(request.body(), ComposeDrawRequest.class);
            int count = body == null || body.count() == null ? 10 : body.count();
            List<Question> drawn = composeService.draw(body == null ? null : body.categoryId(),
                    body == null ? null : body.tags(), body == null ? null : body.types(),
                    body == null ? null : body.difficulties(), count);
            return PluginHttpResponse.ok(Map.of(
                    "questions", drawn.stream()
                            .map(question -> Views.questionView(question, categoryService.categoryName(question.categoryId())))
                            .toList()));
        });
    }

    /** 导出 Word：内置模板渲染，附件下载。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/compose/export-word", permission = QuestionBankPlugin.COMPOSE_PERMISSION)
    public PluginHttpResponse exportWord(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            ComposeExportRequest body = json.read(request.body(), ComposeExportRequest.class);
            if (body == null) {
                throw new IllegalArgumentException("导出参数不能为空");
            }
            List<Question> questions = composeService.byIds(body.questionIds());
            boolean withAnswers = Boolean.TRUE.equals(body.withAnswers());
            PluginRenderedDocument document = composeService.exportWord(
                    body.title(), body.description(), questions, withAnswers);
            String filename = (body.title() == null || body.title().isBlank() ? "试题卷" : body.title().trim()) + ".docx";
            return HttpSupport.download(filename, document.contentType(), document.content());
        });
    }
}
