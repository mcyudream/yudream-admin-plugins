package online.yudream.base.plugin.todo.application.dto;

import java.util.List;

/**
 * 待办任务视图。
 *
 * <p>{@code overdue} 由服务层按当前时间派生，避免前端各自算一套逾期口径。
 * {@code subTaskTotal} / {@code subTaskDone} 直接给出二级小任务进度，前端无需再遍历。
 */
public record TodoTaskDTO(
        String id,
        String ownerId,
        String ownerName,
        String title,
        String remark,
        String priority,
        String status,
        Long dueAt,
        String dueAtLabel,
        Long completedAt,
        String completedAtLabel,
        String source,
        String assignedBy,
        String assignedByName,
        boolean overdue,
        long createdAt,
        String createdAtLabel,
        long updatedAt,
        String updatedAtLabel,
        List<TodoSubTaskDTO> subTasks,
        int subTaskTotal,
        int subTaskDone,
        /** 参与人 ID 列表（不含归属人）；昵称由前端按 ID 批量回查。 */
        List<String> participantIds,
        /** 任务库可见范围：PRIVATE / DEPT / PUBLIC。 */
        String visibility,
        /** DEPT 可见时的部门 ID 列表（编辑回填用）。 */
        List<String> deptIds,
        /** DEPT 可见时的部门名称快照（展示用）。 */
        List<String> deptNames,
        /** 归档标记：归档任务从默认清单、统计与日程中收起，仅在「已归档」筛选下可见。 */
        boolean archived,
        /** 当前请求者是否为归属人：归属人可编辑删除，参与人只能推进。 */
        boolean owned,
        /** 当前请求者是否为创建者（或管理员）：只有他们能改参与人。 */
        boolean canManageParticipants,
        /** 当前请求者负责的小任务 ID 列表：驱动小任务行的加入/退出按钮。 */
        List<String> mySubTaskIds
) {
}
