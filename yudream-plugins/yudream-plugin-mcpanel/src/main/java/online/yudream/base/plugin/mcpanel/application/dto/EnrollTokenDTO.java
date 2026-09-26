package online.yudream.base.plugin.mcpanel.application.dto;

/**
 * 签发一次性注册令牌的响应：明文 token 只出现这一次。
 */
public record EnrollTokenDTO(String token, long expiresAt) {
}
