package online.yudream.base.plugin.activityproof.domain.enumerate;

public enum VerifyStatus {
    UNVERIFIED,
    PASSED,
    FAILED;

    public static VerifyStatus of(String value) {
        if (value == null || value.isBlank()) {
            return UNVERIFIED;
        }
        try {
            return VerifyStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNVERIFIED;
        }
    }
}
