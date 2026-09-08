package online.yudream.base.plugin.eduverify.domain.enumerate;

/** 认证记录状态。 */
public enum VerificationStatus {
    PENDING("待审核"),
    PENDING_MAIL("等待学信网邮件"),
    PASSED("已通过"),
    REJECTED("已驳回"),
    EXPIRED("已过期"),
    REVOKED("已撤销");

    private final String displayName;

    VerificationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static VerificationStatus of(String code) {
        if (code == null) {
            return null;
        }
        try {
            return VerificationStatus.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
