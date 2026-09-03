package online.yudream.base.plugin.material.application.dto;

import online.yudream.base.plugin.material.domain.MaterialShare;

/** 分享外链视图。url 为插件相对路径，前端用 sdk.http.url 解析成绝对地址。 */
public record ShareView(
        String id,
        String url,
        String note,
        long expiresAt,
        long createdAt,
        String createdByName,
        boolean expired
) {
    public static ShareView from(MaterialShare share, long now) {
        return new ShareView(
                share.token(),
                "/public/share/" + share.token(),
                share.note(),
                share.expiresAt(),
                share.createdAt(),
                share.createdByName(),
                share.expired(now));
    }
}
