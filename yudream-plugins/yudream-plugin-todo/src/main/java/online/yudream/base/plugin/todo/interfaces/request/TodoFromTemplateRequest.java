package online.yudream.base.plugin.todo.interfaces.request;

import java.util.List;

/** 从已有任务复制一条待办的请求体。 */
public record TodoFromTemplateRequest(
        String templateId,
        Long dueAt
) {
}
