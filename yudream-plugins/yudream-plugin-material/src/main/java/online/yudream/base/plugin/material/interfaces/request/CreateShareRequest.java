package online.yudream.base.plugin.material.interfaces.request;

/** 创建分享外链请求。expiresInHours 为 null 表示永久有效。 */
public record CreateShareRequest(Integer expiresInHours, String note) {
}
