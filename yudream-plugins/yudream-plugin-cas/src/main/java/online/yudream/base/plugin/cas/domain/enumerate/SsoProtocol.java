package online.yudream.base.plugin.cas.domain.enumerate;

public enum SsoProtocol {
    /** Apereo CAS service ticket（p3/serviceValidate）。 */
    CAS,
    /** OIDC 授权码 + id_token。 */
    OIDC,
    /** 学院 SSO 中转：/sso/start 授权 + 后端 HMAC 换票（/sso/exchange），code 一次性。 */
    BRIDGE;

    public static SsoProtocol from(String raw) {
        if (raw == null || raw.isBlank()) {
            return CAS;
        }
        try {
            return SsoProtocol.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return CAS;
        }
    }

    public String typeCode() {
        return name().toLowerCase();
    }
}
