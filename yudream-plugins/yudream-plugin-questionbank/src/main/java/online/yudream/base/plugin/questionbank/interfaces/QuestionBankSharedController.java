package online.yudream.base.plugin.questionbank.interfaces;

import java.util.LinkedHashMap;
import java.util.Map;
import online.yudream.base.plugin.questionbank.application.CategoryService;
import online.yudream.base.plugin.questionbank.application.ComposeRecordService;
import online.yudream.base.plugin.questionbank.application.ComposeService;
import online.yudream.base.plugin.questionbank.domain.ComposeRecord;
import online.yudream.base.plugin.questionbank.interfaces.support.HttpSupport;
import online.yudream.base.plugin.questionbank.interfaces.support.Views;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/**
 * 组卷分享公开端点（/compose/shared/{token}）。注解权限留空、匿名可达，
 * 凭分享 token 在线查看组卷内容（含答案与解析），题目实时解析、缺失题目自动跳过。
 */
public final class QuestionBankSharedController {
    private final ComposeRecordService recordService;
    private final ComposeService composeService;
    private final CategoryService categoryService;

    public QuestionBankSharedController(ComposeRecordService recordService, ComposeService composeService,
                                        CategoryService categoryService) {
        this.recordService = recordService;
        this.composeService = composeService;
        this.categoryService = categoryService;
    }

    @PluginHttpEndpoint(method = "GET", path = "/compose/shared/{token}")
    public PluginHttpResponse shared(PluginHttpRequest request) {
        return HttpSupport.guard(() -> {
            String token = HttpSupport.segmentAfter(request.path(), "shared");
            ComposeRecord record = recordService.findByShareToken(token);
            if (record == null) {
                throw new online.yudream.base.plugin.questionbank.application.NotFoundException("分享链接不存在或已取消");
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("title", record.title());
            view.put("description", record.description());
            view.put("ownerName", record.ownerName());
            view.put("createdAt", record.createdAt());
            view.put("questions", composeService.byIds(record.questionIds()).stream()
                    .map(question -> Views.questionView(question, categoryService.categoryName(question.categoryId())))
                    .toList());
            return PluginHttpResponse.ok(view);
        });
    }
}
