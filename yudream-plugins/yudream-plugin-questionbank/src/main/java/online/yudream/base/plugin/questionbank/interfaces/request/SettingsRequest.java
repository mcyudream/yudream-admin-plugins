package online.yudream.base.plugin.questionbank.interfaces.request;

import java.util.List;
import java.util.Map;

/** 全局设置更新负载。字段为 null 时保持不变；空字符串视为清空（provider/model/defaultGroup）。 */
public record SettingsRequest(Boolean practiceEnabled, String aiProviderCode, String aiModelCode,
                              List<Map<String, Object>> qqGroups, String qqDefaultGroup,
                              Integer qqAnswerSeconds, Boolean qqAiGrading) {
}
