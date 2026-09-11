package online.yudream.base.plugin.activityproof.interfaces.theme;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.activityproof.application.service.ActivityProofAppService;
import online.yudream.base.plugin.spi.theme.PluginThemeBlockContext;
import online.yudream.base.plugin.spi.theme.PluginThemeBlockProvider;

/**
 * 主题块「活动广场」：向公开站主题模板贡献近期活动的公开子集。
 * 仅暴露标题/封面/时间/摘要/状态/详情 URL 等公开字段；部门限定与草稿活动由应用服务过滤，不会到达这里。
 */
public final class ActivitySquareThemeBlockProvider implements PluginThemeBlockProvider {
    private final ActivityProofAppService appService;

    public ActivitySquareThemeBlockProvider(ActivityProofAppService appService) {
        this.appService = appService;
    }

    @Override
    public String code() {
        return "activity-square";
    }

    @Override
    public String name() {
        return "活动广场";
    }

    @Override
    public Object data(PluginThemeBlockContext ctx) {
        List<Map<String, Object>> activities = appService.publicSquareActivities(ctx.limit()).stream()
                .map(activity -> {
                    var view = appService.toPublicDTO(activity, false);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", view.id());
                    item.put("title", view.title());
                    item.put("summary", view.summary());
                    item.put("cover", view.coverUrl());
                    item.put("url", view.url());
                    item.put("statusKey", view.statusKey());
                    item.put("statusText", view.statusText());
                    item.put("meta", view.meta());
                    return item;
                })
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activities", activities);
        data.put("count", activities.size());
        return data;
    }
}
