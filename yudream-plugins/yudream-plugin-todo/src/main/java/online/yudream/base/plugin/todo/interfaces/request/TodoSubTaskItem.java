package online.yudream.base.plugin.todo.interfaces.request;

import java.util.List;

/**
 * 二级小任务输入项：标题 + 负责人 + 可选的单独优先级/截止时间。
 *
 * <p>负责人在 UI 中单独指派，未指派的小任务对全体任务参与人生效；
 * {@code priority}/{@code dueAt} 为空表示跟随宿主任务的整体设置。
 */
public record TodoSubTaskItem(
        String title,
        List<String> assigneeIds,
        String priority,
        Long dueAt
) {

    public String safeTitle() {
        return title == null ? "" : title;
    }

    public List<String> safeAssigneeIds() {
        return assigneeIds == null ? List.of() : assigneeIds;
    }

    public String safePriority() {
        return priority == null ? "" : priority;
    }

    public Long safeDueAt() {
        return dueAt == null || dueAt <= 0 ? null : dueAt;
    }
}
