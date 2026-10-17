package online.yudream.base.plugin.todo.application.cmd;

import java.util.List;
import online.yudream.base.plugin.todo.interfaces.request.TodoSubTaskItem;

/** 待办字段保存命令（创建与编辑共用）。 */
public record TodoTaskSaveCmd(
        String title,
        String remark,
        String priority,
        Long dueAt,
        /** 二级小任务（含负责人）；按标题复用已有小任务以保留勾选状态。 */
        List<TodoSubTaskItem> subTasks,
        /** 参与人用户 ID 列表；只有创建者或管理员提交的会被采纳。 */
        List<String> participantIds,
        /** 任务库可见范围：PRIVATE / DEPT / PUBLIC，空按 PRIVATE 处理。 */
        String visibility,
        /** DEPT 可见时的部门 ID 列表。 */
        List<String> deptIds
) {

    public List<TodoSubTaskItem> safeSubTasks() {
        return subTasks == null ? List.of() : subTasks;
    }

    public List<String> safeParticipantIds() {
        return participantIds == null ? List.of() : participantIds;
    }

    public List<String> safeDeptIds() {
        return deptIds == null ? List.of() : deptIds;
    }
}
