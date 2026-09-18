package online.yudream.base.plugin.ymcl.api;

import java.util.Map;

/**
 * 数据拉取与动作执行的调用上下文（YAP §6.6/§6.7）。
 *
 * {@code userId} 为发起请求的已认证用户；匿名请求为 null。
 * {@code query} 为请求查询串的扁平化首值表（page/pageSize 已单独解析，
 * 仍原样保留在表内）；详情类数据源用它按 id 等参数定位记录。
 */
public record YmclDataContext(
        int page,
        int pageSize,
        Long userId,
        Map<String, String> query) {

    /** 兼容旧三参构造：无查询参数。消费方只调访问器，新增组件二进制兼容。 */
    public YmclDataContext(int page, int pageSize, Long userId) {
        this(page, pageSize, userId, Map.of());
    }

    /** 读查询参数首值，缺失或空白返回 null。 */
    public String param(String name) {
        String value = query == null ? null : query.get(name);
        return value == null || value.isBlank() ? null : value;
    }
}
