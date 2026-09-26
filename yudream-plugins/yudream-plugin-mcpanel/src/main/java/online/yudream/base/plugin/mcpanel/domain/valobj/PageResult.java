package online.yudream.base.plugin.mcpanel.domain.valobj;

import java.util.List;

/**
 * 标准分页结果：records + total（过滤后的真实总数）。
 */
public record PageResult<T>(List<T> records, long total, int page, int size) {

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        return new PageResult<>(List.copyOf(records), total, page, size);
    }
}
