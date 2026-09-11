package online.yudream.base.plugin.questionbank.interfaces;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.questionbank.application.CategoryService;
import online.yudream.base.plugin.questionbank.application.QuestionService;
import online.yudream.base.plugin.questionbank.bootstrap.QuestionBankPlugin;
import online.yudream.base.plugin.questionbank.interfaces.support.HttpSupport;
import online.yudream.base.plugin.questionbank.interfaces.support.Views;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 随机抽题开放 API（/draw）：供 API Key 或前端调用，返回含答案与解析的题目全量信息。
 * 使用 USE 权限（plugin:questionbank:use），不走 /me 或 /admin 前缀；
 * 前端「抽题 API」试用页与 API Key（X-API-Key）共用本控制器。
 */
public final class QuestionBankDrawController {
    private final QuestionService questionService;
    private final CategoryService categoryService;

    public QuestionBankDrawController(QuestionService questionService, CategoryService categoryService) {
        this.questionService = questionService;
        this.categoryService = categoryService;
    }

    @PluginHttpEndpoint(method = "GET", path = "/draw", permission = QuestionBankPlugin.USE_PERMISSION)
    public PluginHttpResponse draw(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String categoryId = HttpSupport.first(request, "categoryId");
            List<String> tags = csv(HttpSupport.first(request, "tags"));
            List<String> types = csv(HttpSupport.first(request, "type"));
            Integer difficulty = parseDifficulty(HttpSupport.first(request, "difficulty"));
            Long seed = parseSeed(HttpSupport.first(request, "seed"));
            int count = parseCount(HttpSupport.first(request, "count"));

            QuestionService.DrawResult result = questionService.randomDraw(
                    categoryId, tags, types, difficulty, seed, count);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("questions", result.questions().stream()
                    .map(question -> Views.questionView(question,
                            categoryService.categoryName(question.categoryId())))
                    .toList());
            body.put("seed", String.valueOf(result.seed()));
            body.put("total", result.total());
            return PluginHttpResponse.ok(body);
        });
    }

    /** 抽题筛选选项：分类与标签，权限与抽题 API 相同，避免试用页依赖 view/manage。 */
    @PluginHttpEndpoint(method = "GET", path = "/draw/options", permission = QuestionBankPlugin.USE_PERMISSION)
    public PluginHttpResponse options(PluginHttpRequest request) {
        return HttpSupport.guard(() -> PluginHttpResponse.ok(Map.of(
                "categories", categoryService.listWithCounts(),
                "tags", questionService.listTags())));
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private static Integer parseDifficulty(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("difficulty 必须是数字");
        }
    }

    private static Long parseSeed(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("seed 必须是整数");
        }
    }

    private static int parseCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("count 必须是数字");
        }
    }
}
