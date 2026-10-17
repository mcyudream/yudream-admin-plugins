package online.yudream.base.plugin.todo.application.dto;

import java.util.Map;

/** 个人待办概览：主页卡片与用户页头部共用。 */
public record TodoOverviewDTO(
        long total,
        long pending,
        long done,
        long overdue,
        /** 对我可见且已归档的任务数；归档任务不进默认清单，仅在「已归档」筛选下可见。 */
        long archived,
        long dueToday,
        int completionRate,
        java.util.List<TodoTaskDTO> upcoming,
        /** 未完成任务按优先级统计，键为 {@code TodoPriority} 的 code。 */
        Map<String, Long> byPriority
) {
}
