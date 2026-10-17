package online.yudream.base.plugin.todo.interfaces.request;

import java.util.List;

/**
 * 按小任务 id 更新负责人的输入项：只携带小任务标识与被指派的成员 ID 列表，
 * 标题与勾选状态由后端保留，避免派发已有任务时把小任务的其他状态冲掉。
 */
public record TodoSubTaskAssigneeItem(String id, List<String> assigneeIds) {

    public String safeId() {
        return id == null ? "" : id;
    }

    public List<String> safeAssigneeIds() {
        return assigneeIds == null ? List.of() : assigneeIds;
    }
}
