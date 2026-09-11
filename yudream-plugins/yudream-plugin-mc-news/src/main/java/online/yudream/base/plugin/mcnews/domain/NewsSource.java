package online.yudream.base.plugin.mcnews.domain;

import java.util.List;

/**
 * 新闻源配置。type 决定解析器：mcnet 为 minecraft.net 文章列表 JSON，
 * zendesk 为 minecraftfeedback 帮助中心文章 API。
 */
public record NewsSource(
        String id,
        String name,
        String type,
        String url,
        List<String> keywords,
        boolean enabled,
        boolean builtin,
        long createdAt
) {
    public static final String TYPE_MCNET = "mcnet";
    public static final String TYPE_ZENDESK = "zendesk";

    public NewsSource {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    public boolean mcnet() {
        return TYPE_MCNET.equals(type);
    }
}
