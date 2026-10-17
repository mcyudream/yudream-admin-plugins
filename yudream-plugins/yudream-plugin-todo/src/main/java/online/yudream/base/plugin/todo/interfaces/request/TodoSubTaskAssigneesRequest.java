package online.yudream.base.plugin.todo.interfaces.request;

import java.util.List;

/** 派发已有任务时批量更新小任务负责人的请求体。 */
public record TodoSubTaskAssigneesRequest(List<TodoSubTaskAssigneeItem> items) {

    public List<TodoSubTaskAssigneeItem> safeItems() {
        return items == null ? List.of() : items;
    }
}
