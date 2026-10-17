package online.yudream.base.plugin.todo.application.dto;

/** 平台侧待办统计（仅管理员可见）。 */
public record TodoAdminStatsDTO(
        long total,
        long pending,
        long done,
        long overdue,
        /** 全平台已归档任务数；归档任务不进默认清单，仅在「已归档」筛选下可见。 */
        long archived
) {
}
