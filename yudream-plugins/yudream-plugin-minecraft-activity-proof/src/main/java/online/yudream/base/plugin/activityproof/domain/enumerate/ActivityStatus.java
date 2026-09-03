package online.yudream.base.plugin.activityproof.domain.enumerate;

public enum ActivityStatus {
    DRAFT,
    PUBLISHED,
    CLOSED;

    public static ActivityStatus of(String value) {
        if (value == null || value.isBlank()) {
            return DRAFT;
        }
        try {
            return ActivityStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DRAFT;
        }
    }
}
