package online.yudream.base.plugin.ysm.domain;

public enum ReviewStatus {
    APPROVED, PENDING, REJECTED;

    public static ReviewStatus stored(Object value) {
        if (value == null) return APPROVED; // Records predating moderation remain usable.
        try { return valueOf(String.valueOf(value)); }
        catch (IllegalArgumentException ignored) { return PENDING; }
    }
}
