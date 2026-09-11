package online.yudream.base.plugin.mcnews.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import online.yudream.base.plugin.mcnews.domain.NewsSource;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;

/**
 * 新闻源管理：内置三个默认源（官网新闻、反馈隧道 Beta/Release），首次启用时播种。
 * 内置源可编辑、启停，不可删除；自定义源上限 20。
 */
public final class NewsSourceService {
    public static final int MAX_SOURCES = 20;

    private final McNewsStore store;

    public NewsSourceService(McNewsStore store) {
        this.store = store;
    }

    /** 首次启用时播种内置源；已有配置则不动。 */
    public void seedBuiltinsIfEmpty() {
        if (!store.all(McNewsStore.COL_SOURCES).isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        save(new NewsSource("mcnet-news", "Minecraft 官网新闻", NewsSource.TYPE_MCNET,
                "https://www.minecraft.net/content/minecraftnet/language-masters/zh-hans/_jcr_content.articles.page-1.json",
                List.of("A Minecraft Java"), true, true, now));
        save(new NewsSource("fb-beta", "反馈隧道 Beta & Preview", NewsSource.TYPE_ZENDESK,
                "https://minecraftfeedback.zendesk.com/api/v2/help_center/en-us/sections/360001185332/articles?per_page=5",
                List.of(), true, true, now));
        save(new NewsSource("fb-release", "反馈隧道 Release 候选/正式版", NewsSource.TYPE_ZENDESK,
                "https://minecraftfeedback.zendesk.com/api/v2/help_center/en-us/sections/360001186971/articles?per_page=5",
                List.of(), true, true, now));
    }

    public List<NewsSource> list() {
        List<NewsSource> sources = new ArrayList<>();
        for (Map<String, Object> doc : store.all(McNewsStore.COL_SOURCES)) {
            NewsSource source = fromDoc(doc);
            if (source != null) {
                sources.add(source);
            }
        }
        sources.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        return sources;
    }

    public List<NewsSource> listEnabled() {
        return list().stream().filter(NewsSource::enabled).toList();
    }

    public NewsSource require(String id) {
        NewsSource source = find(id);
        if (source == null) {
            throw new IllegalArgumentException("新闻源不存在");
        }
        return source;
    }

    public NewsSource find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return store.find(McNewsStore.COL_SOURCES, id).map(NewsSourceService::fromDoc).orElse(null);
    }

    public NewsSource create(String name, String type, String url, List<String> keywords, boolean enabled) {
        if (store.all(McNewsStore.COL_SOURCES).size() >= MAX_SOURCES) {
            throw new IllegalArgumentException("新闻源数量已达上限（" + MAX_SOURCES + "）");
        }
        NewsSource source = new NewsSource("src-" + UUID.randomUUID().toString().substring(0, 8),
                requireName(name), requireType(type), requireUrl(url), cleanKeywords(keywords), enabled, false,
                System.currentTimeMillis());
        save(source);
        return source;
    }

    public NewsSource update(String id, String name, String type, String url, List<String> keywords, Boolean enabled) {
        NewsSource existing = require(id);
        NewsSource updated = new NewsSource(existing.id(),
                name == null ? existing.name() : requireName(name),
                type == null ? existing.type() : requireType(type),
                url == null ? existing.url() : requireUrl(url),
                keywords == null ? existing.keywords() : cleanKeywords(keywords),
                enabled == null ? existing.enabled() : enabled,
                existing.builtin(), existing.createdAt());
        save(updated);
        return updated;
    }

    /** 仅内置源受保护：删除内置源报业务错误。 */
    public void delete(String id) {
        NewsSource existing = require(id);
        if (existing.builtin()) {
            throw new IllegalArgumentException("内置新闻源不可删除，可停用");
        }
        store.delete(McNewsStore.COL_SOURCES, existing.id());
    }

    private void save(NewsSource source) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", source.id());
        doc.put("name", source.name());
        doc.put("type", source.type());
        doc.put("url", source.url());
        doc.put("keywords", source.keywords());
        doc.put("enabled", source.enabled());
        doc.put("builtin", source.builtin());
        doc.put("createdAt", source.createdAt());
        store.save(McNewsStore.COL_SOURCES, source.id(), doc);
    }

    public static NewsSource fromDoc(Map<String, Object> doc) {
        String id = McNewsStore.str(doc, "id");
        if (id.isBlank()) {
            return null;
        }
        return new NewsSource(id,
                McNewsStore.str(doc, "name"),
                McNewsStore.strOr(doc, "type", NewsSource.TYPE_MCNET),
                McNewsStore.str(doc, "url"),
                McNewsStore.stringList(doc, "keywords"),
                McNewsStore.bool(doc, "enabled", true),
                McNewsStore.bool(doc, "builtin", false),
                McNewsStore.longOr(doc, "createdAt", 0));
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("新闻源名称不能为空");
        }
        return name.trim();
    }

    private String requireType(String type) {
        if (!NewsSource.TYPE_MCNET.equals(type) && !NewsSource.TYPE_ZENDESK.equals(type)) {
            throw new IllegalArgumentException("新闻源类型仅支持 mcnet 或 zendesk");
        }
        return type;
    }

    private String requireUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("新闻源地址不能为空");
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("新闻源地址必须以 http(s):// 开头");
        }
        return trimmed;
    }

    private List<String> cleanKeywords(List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        return keywords.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
    }
}
