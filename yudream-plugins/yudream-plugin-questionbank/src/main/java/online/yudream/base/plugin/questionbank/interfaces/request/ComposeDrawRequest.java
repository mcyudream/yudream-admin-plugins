package online.yudream.base.plugin.questionbank.interfaces.request;

import java.util.List;

/** 随机抽题规则（组卷/大屏共用）。字段均为可选，空值不参与过滤。 */
public record ComposeDrawRequest(String categoryId, List<String> tags, List<String> types,
                                 List<Integer> difficulties, Integer count) {
}
