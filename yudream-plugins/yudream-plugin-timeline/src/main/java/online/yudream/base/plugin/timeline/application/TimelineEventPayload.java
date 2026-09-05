package online.yudream.base.plugin.timeline.application;

import java.util.List;

/** 事件创建/更新请求体（Jackson 反序列化；字段均可缺省，由 service 归一与校验）。 */
public record TimelineEventPayload(
        String title,
        String summary,
        String eventDate,
        String dateLabel,
        String eventType,
        String termLabel,
        List<String> outgoingMembers,
        List<String> incomingMembers,
        String coverImage,
        List<String> images,
        String detail,
        Boolean published,
        Integer sort
) {
}
