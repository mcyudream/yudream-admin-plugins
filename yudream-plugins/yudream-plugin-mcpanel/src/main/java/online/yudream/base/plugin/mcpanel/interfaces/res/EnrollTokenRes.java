package online.yudream.base.plugin.mcpanel.interfaces.res;

/**
 * 注册令牌签发响应：明文 token 只出现这一次，服务端仅存摘要。
 */
public record EnrollTokenRes(String token, long expiresAt) {
}
