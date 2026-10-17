package online.yudream.base.plugin.todo.interfaces.request;

/** 二级小任务勾选状态请求体。 */
public record TodoSubTaskStatusRequest(
        Boolean done
) {
}
