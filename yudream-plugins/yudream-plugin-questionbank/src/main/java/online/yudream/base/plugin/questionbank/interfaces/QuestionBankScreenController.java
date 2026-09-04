package online.yudream.base.plugin.questionbank.interfaces;

import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.questionbank.application.CategoryService;
import online.yudream.base.plugin.questionbank.application.ComposeRecordService;
import online.yudream.base.plugin.questionbank.application.ComposeService;
import online.yudream.base.plugin.questionbank.application.PaperService;
import online.yudream.base.plugin.questionbank.bootstrap.QuestionBankPlugin;
import online.yudream.base.plugin.questionbank.domain.ComposeRecord;
import online.yudream.base.plugin.questionbank.domain.Paper;
import online.yudream.base.plugin.questionbank.domain.Question;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.interfaces.request.ComposeDrawRequest;
import online.yudream.base.plugin.questionbank.interfaces.support.HttpSupport;
import online.yudream.base.plugin.questionbank.interfaces.support.Views;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;

/**
 * 大屏抢答接口（/admin/screen/**）。注解权限留空（公开路由页可达），
 * 处理体内手动校验：必须登录且具备 COMPOSE 或 MANAGE 任一权限。
 * 返回带答案的题目视图，仅用于大屏放映，不收集作答。
 */
public final class QuestionBankScreenController {
    private final ComposeService composeService;
    private final PaperService paperService;
    private final CategoryService categoryService;
    private final JsonSupport json;
    private final ComposeRecordService composeRecordService;

    public QuestionBankScreenController(ComposeService composeService, PaperService paperService,
                                        CategoryService categoryService, JsonSupport json,
                                        ComposeRecordService composeRecordService) {
        this.composeService = composeService;
        this.paperService = paperService;
        this.categoryService = categoryService;
        this.json = json;
        this.composeRecordService = composeRecordService;
    }

    /** 已发布题单列表（大屏选题单用）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/screen/papers")
    public PluginHttpResponse papers(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            requireScreenPermission(request);
            return PluginHttpResponse.ok(Map.of(
                    "records", paperService.listPublished().stream().map(Views::paperView).toList()));
        });
    }

    /** 抽题：recordId（组卷记录）> questionIds（组卷中心当前列表）> paperId（题单）> rule（随机规则）。均带答案返回。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/screen/draw")
    public PluginHttpResponse draw(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            requireScreenPermission(request);
            ScreenDrawRequest body = json.read(request.body(), ScreenDrawRequest.class);
            List<Question> drawn;
            String paperName = null;
            if (body != null && body.recordId() != null && !body.recordId().isBlank()) {
                ComposeRecord record = composeRecordService.require(body.recordId());
                drawn = composeService.byIds(record.questionIds());
                paperName = record.title();
            }
            else if (body != null && body.questionIds() != null && !body.questionIds().isEmpty()) {
                drawn = composeService.byIds(body.questionIds());
            }
            else if (body != null && body.paperId() != null && !body.paperId().isBlank()) {
                Paper paper = paperService.requirePublished(body.paperId());
                drawn = paperService.draw(paper);
                paperName = paper.name();
            }
            else {
                ComposeDrawRequest rule = body == null ? null : body.rule();
                int count = rule == null || rule.count() == null ? 10 : rule.count();
                drawn = composeService.draw(rule == null ? null : rule.categoryId(),
                        rule == null ? null : rule.tags(), rule == null ? null : rule.types(),
                        rule == null ? null : rule.difficulties(), count);
            }
            java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("paperName", paperName);
            view.put("questions", drawn.stream()
                    .map(question -> Views.questionView(question, categoryService.categoryName(question.categoryId())))
                    .toList());
            return PluginHttpResponse.ok(view);
        });
    }

    private void requireScreenPermission(PluginHttpRequest request) {
        PluginPrincipal principal = request.principal();
        if (principal == null || principal.userId() == null) {
            throw new IllegalStateException("请先登录后再使用抢答大屏");
        }
        if (!principal.hasPermission(QuestionBankPlugin.COMPOSE_PERMISSION)
                && !principal.hasPermission(QuestionBankPlugin.MANAGE_PERMISSION)) {
            throw new IllegalStateException("没有大屏抢答权限（需要组卷或题库管理权限）");
        }
    }

    /** 大屏抽题负载：recordId / questionIds / paperId / rule 按优先级取其一。 */
    public record ScreenDrawRequest(String recordId, List<String> questionIds, String paperId, ComposeDrawRequest rule) {
    }
}
