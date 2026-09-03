package online.yudream.base.plugin.activityproof.domain.enumerate;

public enum ParticipationStatus {
    JOINED,
    CANCELLED;

    public static ParticipationStatus of(String value) {
        if (value == null || value.isBlank()) {
            return JOINED;
        }
        try {
            return ParticipationStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return JOINED;
        }
    }
}
