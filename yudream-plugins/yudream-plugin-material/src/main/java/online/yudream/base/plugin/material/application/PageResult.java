package online.yudream.base.plugin.material.application;

import java.util.List;

/** 内存分页结果。 */
public record PageResult<T>(List<T> records, long total) {
    public static <T> PageResult<T> of(List<T> records, long total) {
        return new PageResult<>(records, total);
    }
}
