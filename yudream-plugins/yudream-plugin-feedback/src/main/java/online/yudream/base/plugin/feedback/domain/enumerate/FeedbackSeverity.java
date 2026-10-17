package online.yudream.base.plugin.feedback.domain.enumerate;

import java.util.Locale;

/** 严重程度：用户提交时自评，管理端可在受理过程中修正，并作为默认排序依据。 */
public enum FeedbackSeverity {

    LOW("low", "轻微", 0),
    NORMAL("normal", "一般", 1),
    HIGH("high", "严重", 2),
    CRITICAL("critical", "致命", 3);

    private final String code;
    private final String label;
    private final int weight;

    FeedbackSeverity(String code, String label, int weight) {
        this.code = code;
        this.label = label;
        this.weight = weight;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 排序权重，数值越大越紧急。 */
    public int weight() {
        return weight;
    }

    /** 严格解析：空值按「一般」，非法值抛错，用于入参校验。 */
    public static FeedbackSeverity of(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (FeedbackSeverity severity : values()) {
            if (severity.code.equals(normalized)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("严重程度仅支持 low / normal / high / critical");
    }

    /** 宽松解析：用于读取历史数据，无法识别时回退到「一般」。 */
    public static FeedbackSeverity lenient(String raw) {
        try {
            return of(raw);
        }
        catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
