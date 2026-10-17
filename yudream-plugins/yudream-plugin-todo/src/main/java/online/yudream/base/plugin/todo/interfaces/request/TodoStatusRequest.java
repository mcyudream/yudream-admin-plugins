package online.yudream.base.plugin.todo.interfaces.request;

/** 完成状态切换请求；{@code done} 缺失或为 null 时按「未完成」处理。 */
public record TodoStatusRequest(Boolean done) {
}
