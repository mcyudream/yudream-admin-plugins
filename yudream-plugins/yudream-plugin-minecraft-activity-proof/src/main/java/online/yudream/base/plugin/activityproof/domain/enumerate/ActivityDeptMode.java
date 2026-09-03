package online.yudream.base.plugin.activityproof.domain.enumerate;

public enum ActivityDeptMode {
    ALL,
    DEPTS;

    public static ActivityDeptMode of(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return ActivityDeptMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ALL;
        }
    }
}
