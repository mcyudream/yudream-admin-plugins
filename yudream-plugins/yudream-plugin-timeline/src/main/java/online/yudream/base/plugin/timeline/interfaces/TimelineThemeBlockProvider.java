package online.yudream.base.plugin.timeline.interfaces;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.spi.theme.PluginThemeBlockContext;
import online.yudream.base.plugin.spi.theme.PluginThemeBlockProvider;
import online.yudream.base.plugin.timeline.application.TimelineEventService;
import online.yudream.base.plugin.timeline.domain.TimelineEvent;

/**
 * 主题块「大事记」：向公开站主题模板贡献近期已发布事件。
 * 仅暴露标题/时间/摘要等公开字段，事件详情页统一导向公开路由 /timeline。
 */
public final class TimelineThemeBlockProvider implements PluginThemeBlockProvider {
    private static final String PUBLIC_URL = "/timeline";

    private final TimelineEventService eventService;

    public TimelineThemeBlockProvider(TimelineEventService eventService) {
        this.eventService = eventService;
    }

    @Override
    public String code() {
        return "timeline";
    }

    @Override
    public String name() {
        return "大事记";
    }

    @Override
    public Object data(PluginThemeBlockContext ctx) {
        List<TimelineEvent> published = eventService.listPublished();
        int limit = ctx.limit() <= 0 ? published.size() : Math.min(ctx.limit(), published.size());
        List<Map<String, Object>> events = published.stream().limit(limit).map(event -> {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("title", event.title());
            view.put("time", event.dateLabel() == null || event.dateLabel().isBlank()
                    ? event.eventDate() : event.dateLabel());
            view.put("summary", event.summary());
            view.put("url", PUBLIC_URL);
            return view;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", events);
        data.put("count", events.size());
        return data;
    }
}
