package online.yudream.base.plugin.feedback.application.dto;

import java.util.List;

/** 分页结果：记录集合与满足筛选条件的总数。 */
public record FeedbackPageDTO<T>(
        List<T> records,
        long total,
        int page,
        int size
) {
}
