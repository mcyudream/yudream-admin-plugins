package online.yudream.base.plugin.todo.application.dto;

import java.util.List;

/** 日程表中单日的待办聚合。 */
public record TodoScheduleDayDTO(
        /** 日期键，格式 yyyy-MM-dd。 */
        String date,
        int total,
        int done,
        int overdue,
        /** 当日截止的小任务数（未单独设截止的跟随宿主任务计入其截止日）。 */
        int subTasks,
        List<TodoTaskDTO> tasks
) {
}
