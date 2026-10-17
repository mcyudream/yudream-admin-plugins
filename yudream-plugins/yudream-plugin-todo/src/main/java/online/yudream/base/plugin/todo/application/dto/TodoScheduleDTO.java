package online.yudream.base.plugin.todo.application.dto;

import java.util.List;

/**
 * 日程表视图：按自然日聚合当前用户的待办。
 *
 * <p>{@code days} 覆盖查询区间的每一天（含无待办的日子），前端可直接按索引铺网格。
 */
public record TodoScheduleDTO(
        String from,
        String to,
        List<TodoScheduleDayDTO> days
) {
}
