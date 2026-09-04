package online.yudream.base.plugin.questionbank.application;

import java.util.List;

/** 用户抽题筛选条件。count 为期望抽题数（1..50），空条件不参与过滤。 */
public record PracticeFilter(
        String categoryId,
        List<String> tags,
        List<String> types,
        List<Integer> difficulties,
        Integer count
) {
}
