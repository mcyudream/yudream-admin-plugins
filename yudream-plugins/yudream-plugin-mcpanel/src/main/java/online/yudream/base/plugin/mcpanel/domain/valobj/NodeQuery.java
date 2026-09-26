package online.yudream.base.plugin.mcpanel.domain.valobj;

/**
 * 节点分页查询。status 为 online/offline；keyword 模糊匹配名称/端点/上报主机名。
 */
public record NodeQuery(Integer page, Integer size, String status, String keyword) {

    public int pageOrDefault() {
        return page == null || page < 1 ? 1 : page;
    }

    public int sizeOrDefault() {
        if (size == null || size < 1) {
            return 10;
        }
        return Math.min(size, 100);
    }

    public boolean filtered() {
        return (status != null && !status.isBlank()) || (keyword != null && !keyword.isBlank());
    }
}
