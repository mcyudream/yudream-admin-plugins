package online.yudream.base.plugin.activityproof.domain.enumerate;

public enum ActivityBindingType {
    PLAYTIME,
    FORM;

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
