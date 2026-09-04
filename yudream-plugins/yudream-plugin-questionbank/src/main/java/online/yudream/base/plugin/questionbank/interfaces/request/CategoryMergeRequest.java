package online.yudream.base.plugin.questionbank.interfaces.request;

import java.util.List;

/** 分类合并请求体：sourceIds 整体并入 targetId。 */
public record CategoryMergeRequest(String targetId, List<String> sourceIds) {
}
