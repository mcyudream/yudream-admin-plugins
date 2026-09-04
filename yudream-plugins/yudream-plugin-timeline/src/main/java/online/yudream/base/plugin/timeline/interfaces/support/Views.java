package online.yudream.base.plugin.timeline.interfaces.support;

import java.util.LinkedHashMap;
import java.util.Map;
import online.yudream.base.plugin.timeline.domain.TimelineEvent;

/** 事件视图装配：列表视图不含 Markdown 详情，详情视图含全部字段。 */
public final class Views {
    private Views() {
    }

    public static Map<String, Object> summaryView(TimelineEvent event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", event.id());
        view.put("title", event.title());
        view.put("summary", event.summary());
        view.put("eventDate", event.eventDate());
        view.put("dateLabel", event.dateLabel());
        view.put("coverImage", event.coverImage());
        view.put("imageCount", event.images().size());
        view.put("published", event.published());
        view.put("sort", event.sort());
        view.put("createdAt", event.createdAt());
        view.put("updatedAt", event.updatedAt());
        return view;
    }

    public static Map<String, Object> detailView(TimelineEvent event) {
        Map<String, Object> view = summaryView(event);
        view.put("images", event.images());
        view.put("detail", event.detail());
        return view;
    }
}
