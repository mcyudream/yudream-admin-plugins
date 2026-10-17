package online.yudream.base.plugin.todo.interfaces.request;

/** 归档切换请求；{@code archived} 缺失或为 null 时按「取消归档」处理。 */
public record TodoArchiveRequest(Boolean archived) {
}
