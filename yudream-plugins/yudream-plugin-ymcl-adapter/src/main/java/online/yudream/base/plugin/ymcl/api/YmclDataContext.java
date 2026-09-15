package online.yudream.base.plugin.ymcl.api;

/**
 * 数据拉取与动作执行的调用上下文（YAP §6.6/§6.7）。
 *
 * {@code userId} 为发起请求的已认证用户；匿名请求为 null。
 */
public record YmclDataContext(
        int page,
        int pageSize,
        Long userId) {
}
