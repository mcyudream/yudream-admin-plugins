package online.yudream.base.plugin.questionbank.interfaces.request;

import java.util.List;

/** Word 导出负载：自定义标题/说明 + 题目 id 顺序 + 是否附答案解析。 */
public record ComposeExportRequest(String title, String description, List<String> questionIds,
                                   Boolean withAnswers) {
}
