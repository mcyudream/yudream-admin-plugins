package online.yudream.base.plugin.questionbank.interfaces.request;

import java.util.List;

/** 批量删除请求体。 */
public record BatchDeleteRequest(List<String> ids) {
}
