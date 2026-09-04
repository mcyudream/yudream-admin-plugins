package online.yudream.base.plugin.questionbank.domain;

/** 题型。choice 类客观题自动判分；SHORT 简答提交后由用户对照参考答案自评。 */
public enum QuestionType {
    SINGLE("单选题"),
    MULTIPLE("多选题"),
    TRUE_FALSE("判断题"),
    FILL("填空题"),
    SHORT("简答题");

    private final String label;

    QuestionType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isChoice() {
        return this == SINGLE || this == MULTIPLE;
    }

    public static QuestionType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("题型不能为空");
        }
        try {
            return QuestionType.valueOf(raw.trim().toUpperCase());
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知题型：" + raw);
        }
    }
}
