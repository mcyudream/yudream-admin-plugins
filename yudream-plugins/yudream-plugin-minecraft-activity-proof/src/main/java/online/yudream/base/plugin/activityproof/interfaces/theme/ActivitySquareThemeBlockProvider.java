package online.yudream.base.plugin.activityproof.interfaces.theme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.activityproof.application.service.ActivityProofAppService;
import online.yudream.base.plugin.activityproof.domain.aggregate.Activity;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityStatus;
import online.yudream.base.plugin.spi.theme.PluginThemeBlockContext;
import online.yudream.base.plugin.spi.theme.PluginThemeBlockProvider;

/**
 * 主题块「活动广场」：向公开站主题模板贡献近期活动的公开子集。
 * 仅暴露标题/时间/摘要/状态等公开字段；部门限定与草稿活动由应用服务过滤，不会到达这里。
 */
public final class ActivitySquareThemeBlockProvider implements PluginThemeBlockProvider {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
                .map(this::view)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activities", activities);
        data.put("count", activities.size());
        return data;
    }

    private Map<String, Object> view(Activity activity) {
        long now = System.currentTimeMillis();
        boolean ended = activity.status() == ActivityStatus.CLOSED || activity.activityEnded(now);
        boolean started = activity.activityStart() > 0 && now >= activity.activityStart();
        String statusKey = ended ? "ended" : started ? "ongoing" : "upcoming";
        String statusText = ended ? "已结束" : started ? "进行中"
                : activity.signupOpen(now) ? "报名中" : "即将开始";
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("title", activity.title());
        view.put("statusKey", statusKey);
        view.put("statusText", statusText);
        view.put("meta", meta(activity));
        view.put("summary", activity.summary());
        return view;
    }

    /** 活动起止时间合成为单条展示文案，模板直接渲染，无需再做条件拼接。 */
    private static String meta(Activity activity) {
        if (activity.activityStart() <= 0) {
            return "";
        }
        String start = TIME_FORMAT.format(Instant.ofEpochMilli(activity.activityStart()).atZone(ZoneId.systemDefault()));
        if (activity.activityEnd() <= activity.activityStart()) {
            return start + " 起";
        }
        String end = TIME_FORMAT.format(Instant.ofEpochMilli(activity.activityEnd()).atZone(ZoneId.systemDefault()));
        return start + " 至 " + end;
    }
}
