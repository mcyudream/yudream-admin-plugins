package online.yudream.base.plugin.union.domain;

/** 联合认证业务异常（code 供协议错误体，status 为 HTTP 状态）。 */
public class UnionBusinessException extends RuntimeException {

    private final String code;
    private final int status;

    public UnionBusinessException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static UnionBusinessException badToken() {
        return badToken("注册令牌无效");
    }

    /** code/status 保持协议值不变，仅细分文案（查无此令牌 / 已过期）。 */
    public static UnionBusinessException badToken(String message) {
        return new UnionBusinessException("auth.badToken", 401, message);
    }

    public static UnionBusinessException tokenConsumed() {
        return new UnionBusinessException("auth.tokenConsumed", 409, "注册令牌已被使用");
    }

    public static UnionBusinessException rateLimited() {
        return new UnionBusinessException("auth.rateLimited", 429, "请求过于频繁，请稍后重试");
    }

    public static UnionBusinessException invalid(String message) {
        return new UnionBusinessException("invalid-request", 400, message);
    }

    public static UnionBusinessException peerUnreachable(String message) {
        return new UnionBusinessException("peer-unreachable", 502, message);
    }
}
