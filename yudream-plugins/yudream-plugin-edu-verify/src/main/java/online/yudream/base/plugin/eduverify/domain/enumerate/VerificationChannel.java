package online.yudream.base.plugin.eduverify.domain.enumerate;

/** 认证渠道。CARSI 渠道仅作模型预留，本期不实现。 */
public enum VerificationChannel {
    EMAIL("教育邮箱"),
    CHSI("学信网在线验证码"),
    MANUAL("人工审核"),
    CARSI("CARSI（预留）");

    private final String displayName;

    VerificationChannel(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static VerificationChannel of(String code) {
        if (code == null) {
            return null;
        }
        try {
            return VerificationChannel.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
