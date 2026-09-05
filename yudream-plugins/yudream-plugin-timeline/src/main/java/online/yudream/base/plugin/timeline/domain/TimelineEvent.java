package online.yudream.base.plugin.timeline.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大事记事件。eventDate 是权威排序键（yyyy-MM-dd），dateLabel 为可选的自定义展示文案
 * （如「2020 年春」），为空时前端按 eventDate 格式化展示。文本字段入库前统一归一为非 null，
 * 规避宿主文档存储的 null NPE。eventType 缺省 ARTICLE；termLabel 与换届名册仅 ELECTION 使用。
 */
public record TimelineEvent(
        String id,
        String title,
        String summary,
        String eventDate,
        String dateLabel,
        TimelineEventType eventType,
        String termLabel,
        List<String> outgoingMembers,
        List<String> incomingMembers,
        String coverImage,
        List<String> images,
        String detail,
        boolean published,
        int sort,
        long createdAt,
        long updatedAt
) {
    public Map<String, Object> toDoc() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("title", title == null ? "" : title);
        doc.put("summary", summary == null ? "" : summary);
        doc.put("eventDate", eventDate == null ? "" : eventDate);
        doc.put("dateLabel", dateLabel == null ? "" : dateLabel);
        doc.put("eventType", (eventType == null ? TimelineEventType.ARTICLE : eventType).name());
        doc.put("termLabel", termLabel == null ? "" : termLabel);
        doc.put("outgoingMembers", outgoingMembers == null ? List.of() : List.copyOf(outgoingMembers));
        doc.put("incomingMembers", incomingMembers == null ? List.of() : List.copyOf(incomingMembers));
        doc.put("coverImage", coverImage == null ? "" : coverImage);
        doc.put("images", images == null ? List.of() : List.copyOf(images));
        doc.put("detail", detail == null ? "" : detail);
        doc.put("published", published);
        doc.put("sort", sort);
        doc.put("createdAt", createdAt);
        doc.put("updatedAt", updatedAt);
        return doc;
    }

    public static TimelineEvent fromDoc(Map<String, Object> doc) {
        return new TimelineEvent(
                DocValues.str(doc, "id"),
                DocValues.strOr(doc, "title", ""),
                DocValues.strOr(doc, "summary", ""),
                DocValues.strOr(doc, "eventDate", ""),
                DocValues.strOr(doc, "dateLabel", ""),
                TimelineEventType.fromDoc(DocValues.strOr(doc, "eventType", "")),
                DocValues.strOr(doc, "termLabel", ""),
                DocValues.stringList(doc, "outgoingMembers"),
                DocValues.stringList(doc, "incomingMembers"),
                DocValues.strOr(doc, "coverImage", ""),
                DocValues.stringList(doc, "images"),
                DocValues.strOr(doc, "detail", ""),
                DocValues.bool(doc, "published", false),
                DocValues.integer(doc, "sort"),
                DocValues.lng(doc, "createdAt"),
                DocValues.lng(doc, "updatedAt")
        );
    }
}
