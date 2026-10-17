package online.yudream.base.plugin.feedback.domain.enumerate;

import java.util.Locale;

/** 反馈类型：用户提交时选择，决定管理端的默认受理方式。 */
public enum FeedbackType {

    BUG("bug", "缺陷反馈"),
    SUGGESTION("suggestion", "功能建议"),
    QUESTION("question", "使用咨询"),
    OTHER("other", "其他");

    private final String code;
    private final String label;

    FeedbackType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 严格解析：空值按其他，非法值抛错，用于入参校验。 */
    public static FeedbackType of(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (FeedbackType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("反馈类型仅支持 bug / suggestion / question / other");
    }

    /** 宽松解析：用于读取历史数据，无法识别时回退到「其他」。 */
    public static FeedbackType lenient(String raw) {
        try {
            return of(raw);
        }
        catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
