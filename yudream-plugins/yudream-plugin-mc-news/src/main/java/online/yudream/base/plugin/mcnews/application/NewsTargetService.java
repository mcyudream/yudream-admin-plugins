package online.yudream.base.plugin.mcnews.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import online.yudream.base.plugin.mcnews.domain.NewsTarget;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;

/**
 * 推送目标管理。ownerUserId 为空 = 管理员全局目标（群聊/Webhook）；
 * 非空 = 用户个人 Webhook（每用户上限 5，仅本人可读写）。
 */
public final class NewsTargetService {
    public static final int MAX_GLOBAL_TARGETS = 20;
    public static final int MAX_USER_TARGETS = 5;

    private final McNewsStore store;

    public NewsTargetService(McNewsStore store) {
        this.store = store;
    }

    public List<NewsTarget> listGlobal() {
        List<NewsTarget> targets = new ArrayList<>();
        for (Map<String, Object> doc : store.all(McNewsStore.COL_TARGETS)) {
            NewsTarget target = NewsTargetService.fromDoc(doc);
            if (target != null && target.ownerUserId() == null) {
                targets.add(target);
            }
        }
        targets.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        return targets;
    }

    public List<NewsTarget> listGlobalEnabled() {
        return listGlobal().stream().filter(NewsTarget::enabled).toList();
    }

    /** 用户个人目标：按归属过滤，禁止读取他人目标。 */
    public List<NewsTarget> listByOwner(String ownerUserId) {
        List<NewsTarget> targets = new ArrayList<>();
        for (Map<String, Object> doc : store.byField(McNewsStore.COL_TARGETS, "ownerUserId", ownerUserId, 1, 50)) {
            NewsTarget target = fromDoc(doc);
            if (target != null) {
                targets.add(target);
            }
        }
        targets.sort((a, b) -> Long.compare(a.createdAt(), b.createdAt()));
        return targets;
    }

    public List<NewsTarget> listAllEnabled() {
        List<NewsTarget> targets = new ArrayList<>();
        for (Map<String, Object> doc : store.all(McNewsStore.COL_TARGETS)) {
            NewsTarget target = fromDoc(doc);
            if (target != null && target.enabled()) {
                targets.add(target);
            }
        }
        return targets;
    }

    public NewsTarget require(String id) {
        NewsTarget target = find(id);
        if (target == null) {
            throw new IllegalArgumentException("推送目标不存在");
        }
        return target;
    }

    public NewsTarget find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return store.find(McNewsStore.COL_TARGETS, id).map(NewsTargetService::fromDoc).orElse(null);
    }

    /** 按 id + 归属读取：不属于该用户时视为不存在。 */
    public NewsTarget requireOwned(String id, String ownerUserId) {
        NewsTarget target = require(id);
        if (!target.ownedBy(ownerUserId)) {
            throw new IllegalArgumentException("推送目标不存在");
        }
        return target;
    }

    public NewsTarget createGlobal(String name, String type, Boolean enabled, String connectionId, String channelId,
                                   String channelName, String webhookUrl, List<NewsTarget.WebhookHeader> headers, Boolean markdown) {
        if (listGlobal().size() >= MAX_GLOBAL_TARGETS) {
            throw new IllegalArgumentException("推送目标数量已达上限（" + MAX_GLOBAL_TARGETS + "）");
        }
        return create(name, type, enabled, null, connectionId, channelId, channelName, webhookUrl, headers, markdown);
    }

    public NewsTarget createUserWebhook(String ownerUserId, String name, String webhookUrl,
                                        List<NewsTarget.WebhookHeader> headers, Boolean enabled) {
        if (listByOwner(ownerUserId).size() >= MAX_USER_TARGETS) {
            throw new IllegalArgumentException("个人 Webhook 数量已达上限（" + MAX_USER_TARGETS + "）");
        }
        return create(name, NewsTarget.TYPE_WEBHOOK, enabled == null || enabled, ownerUserId,
                null, null, null, webhookUrl, headers, false);
    }

    private NewsTarget create(String name, String type, Boolean enabled, String ownerUserId, String connectionId,
                              String channelId, String channelName, String webhookUrl,
                              List<NewsTarget.WebhookHeader> headers, Boolean markdown) {
        NewsTarget target = new NewsTarget("tgt-" + UUID.randomUUID().toString().substring(0, 8),
                requireName(name), requireType(type), enabled == null || enabled, ownerUserId,
                connectionId == null ? "" : connectionId.trim(),
                channelId == null ? "" : channelId.trim(),
                channelName == null ? "" : channelName.trim(),
                requireWebhookUrl(webhookUrl, NewsTarget.TYPE_WEBHOOK.equals(type)),
                cleanHeaders(headers), markdown == null || markdown, System.currentTimeMillis());
        validateChannel(target);
        save(target);
        return target;
    }

    public NewsTarget updateGlobal(String id, String name, String type, Boolean enabled, String connectionId,
                                   String channelId, String channelName, String webhookUrl,
                                   List<NewsTarget.WebhookHeader> headers, Boolean markdown) {
        NewsTarget existing = require(id);
        if (existing.ownerUserId() != null) {
            throw new IllegalArgumentException("个人推送目标请由用户本人维护");
        }
        NewsTarget updated = new NewsTarget(existing.id(),
                name == null ? existing.name() : requireName(name),
                type == null ? existing.type() : requireType(type),
                enabled == null ? existing.enabled() : enabled,
                existing.ownerUserId(),
                connectionId == null ? existing.connectionId() : connectionId.trim(),
                channelId == null ? existing.channelId() : channelId.trim(),
                channelName == null ? existing.channelName() : channelName.trim(),
                webhookUrl == null ? existing.webhookUrl() : requireWebhookUrl(webhookUrl,
                        NewsTarget.TYPE_WEBHOOK.equals(type == null ? existing.type() : type)),
                headers == null ? existing.headers() : cleanHeaders(headers),
                markdown == null ? existing.markdown() : markdown,
                existing.createdAt());
        validateChannel(updated);
        save(updated);
        return updated;
    }

    public NewsTarget updateOwned(String id, String ownerUserId, String name, String webhookUrl,
                                  List<NewsTarget.WebhookHeader> headers, Boolean enabled) {
        NewsTarget existing = requireOwned(id, ownerUserId);
        NewsTarget updated = new NewsTarget(existing.id(),
                name == null ? existing.name() : requireName(name),
                existing.type(), enabled == null ? existing.enabled() : enabled, existing.ownerUserId(),
                existing.connectionId(), existing.channelId(), existing.channelName(),
                webhookUrl == null ? existing.webhookUrl() : requireWebhookUrl(webhookUrl, true),
                headers == null ? existing.headers() : cleanHeaders(headers),
                existing.markdown(), existing.createdAt());
        save(updated);
        return updated;
    }

    public void delete(String id) {
        store.delete(McNewsStore.COL_TARGETS, require(id).id());
    }

    public void deleteOwned(String id, String ownerUserId) {
        store.delete(McNewsStore.COL_TARGETS, requireOwned(id, ownerUserId).id());
    }

    private void save(NewsTarget target) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", target.id());
        doc.put("name", target.name());
        doc.put("type", target.type());
        doc.put("enabled", target.enabled());
        if (target.ownerUserId() != null) {
            doc.put("ownerUserId", target.ownerUserId());
        }
        doc.put("connectionId", target.connectionId());
        doc.put("channelId", target.channelId());
        doc.put("channelName", target.channelName());
        doc.put("webhookUrl", target.webhookUrl());
        List<Map<String, Object>> headers = new ArrayList<>();
        for (NewsTarget.WebhookHeader header : target.headers()) {
            headers.add(Map.of("key", header.key(), "value", header.value()));
        }
        doc.put("headers", headers);
        doc.put("markdown", target.markdown());
        doc.put("createdAt", target.createdAt());
        store.save(McNewsStore.COL_TARGETS, target.id(), doc);
    }

    public static NewsTarget fromDoc(Map<String, Object> doc) {
        String id = McNewsStore.str(doc, "id");
        if (id.isBlank()) {
            return null;
        }
        String owner = McNewsStore.str(doc, "ownerUserId");
        List<NewsTarget.WebhookHeader> headers = new ArrayList<>();
        for (Map<String, Object> header : McNewsStore.mapList(doc, "headers")) {
            String key = McNewsStore.str(header, "key");
            if (!key.isBlank()) {
                headers.add(new NewsTarget.WebhookHeader(key, McNewsStore.str(header, "value")));
            }
        }
        return new NewsTarget(id,
                McNewsStore.str(doc, "name"),
                McNewsStore.strOr(doc, "type", NewsTarget.TYPE_WEBHOOK),
                McNewsStore.bool(doc, "enabled", true),
                owner.isBlank() ? null : owner,
                McNewsStore.str(doc, "connectionId"),
                McNewsStore.str(doc, "channelId"),
                McNewsStore.str(doc, "channelName"),
                McNewsStore.str(doc, "webhookUrl"),
                headers,
                McNewsStore.bool(doc, "markdown", true),
                McNewsStore.longOr(doc, "createdAt", 0));
    }

    private void validateChannel(NewsTarget target) {
        if (target.messaging() && (target.connectionId().isBlank() || target.channelId().isBlank())) {
            throw new IllegalArgumentException("群聊推送目标必须选择消息连接与群聊");
        }
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("推送目标名称不能为空");
        }
        return name.trim();
    }

    private String requireType(String type) {
        if (!NewsTarget.TYPE_MESSAGING.equals(type) && !NewsTarget.TYPE_WEBHOOK.equals(type)) {
            throw new IllegalArgumentException("推送目标类型仅支持 messaging 或 webhook");
        }
        return type;
    }

    private String requireWebhookUrl(String url, boolean required) {
        if (url == null || url.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("Webhook 地址不能为空");
            }
            return "";
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("Webhook 地址必须以 http(s):// 开头");
        }
        return trimmed;
    }

    private List<NewsTarget.WebhookHeader> cleanHeaders(List<NewsTarget.WebhookHeader> headers) {
        if (headers == null) {
            return List.of();
        }
        List<NewsTarget.WebhookHeader> result = new ArrayList<>();
        for (NewsTarget.WebhookHeader header : headers) {
            if (header == null || header.key() == null || header.key().isBlank()) {
                continue;
            }
            result.add(new NewsTarget.WebhookHeader(header.key().trim(), header.value() == null ? "" : header.value()));
        }
        return result;
    }
}
