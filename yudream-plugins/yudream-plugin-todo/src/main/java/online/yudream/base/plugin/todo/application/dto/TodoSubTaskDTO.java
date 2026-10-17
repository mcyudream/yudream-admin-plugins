package online.yudream.base.plugin.todo.application.dto;

import java.util.List;

/** 二级小任务视图。 */
public record TodoSubTaskDTO(
        String id,
        String title,
        boolean done,
        /** 被指派负责该小任务的成员 ID；前端按 ID 批量回查昵称。 */
        List<String> assigneeIds,
        /** 小任务单独设置的优先级；null 表示跟随任务整体设置。 */
        String priority,
        /** 单独设置的截止时间（epoch 毫秒）；null 表示跟随任务整体设置。 */
        Long dueAt,
        /** 小任务最近一次内容变更时间（epoch 毫秒）。 */
        long updatedAt,
        /** 格式化后的更新时间展示文案。 */
        String updatedAtLabel
) {
}
