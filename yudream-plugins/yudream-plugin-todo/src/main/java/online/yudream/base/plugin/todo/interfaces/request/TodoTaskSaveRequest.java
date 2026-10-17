package online.yudream.base.plugin.todo.interfaces.request;

import java.util.List;

/** 待办保存请求体。{@code subTasks} 为二级小任务（含负责人），{@code participantIds} 为参与人用户 ID。 */
public record TodoTaskSaveRequest(
        String title,
        String remark,
        String priority,
        Long dueAt,
        List<TodoSubTaskItem> subTasks,
        List<String> participantIds,
        String visibility,
        List<String> deptIds
) {
}
