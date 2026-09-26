package online.yudream.base.plugin.activityproof.domain.enumerate;

public enum ActivityBindingType {
    PLAYTIME,
    FORM,
    QUIZ,
    /** 高级自定义计分：把时长/表单/答题暴露成参数变量，按管理员书写的计算式求分，按达标区间判定。 */
    ADVANCED;

    public static ActivityBindingType of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ActivityBindingType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
