package online.yudream.base.plugin.mcguess.infrastructure;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McItemEntry;

/**
 * 贴图按需从 mc-wiki provider 拉取并缓存为模板使用的 data URI；物品 → textureKey 同样按次点查缓存，
 * 启用期不搬运任何物品清单。provider 引用每次经 supplier 重新解析，mc-wiki 重载/禁用期间降级为无图标
 * 而不是持有过期 API 对象；生效数据版本（与目录同一来源）变化时清空缓存，避免继续引用旧版本贴图。
 */
public final class IconSupport {
    private final Supplier<Optional<McWikiApi>> wikiApi;
    private final Supplier<Optional<String>> effectiveVersion;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, Optional<String>> textureKeys = new ConcurrentHashMap<>();
    private volatile String version;

    public IconSupport(Supplier<Optional<McWikiApi>> wikiApi, Supplier<Optional<String>> effectiveVersion) {
        this.wikiApi = wikiApi;
        this.effectiveVersion = effectiveVersion;
    }

    public String dataUri(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        Optional<McWikiApi> api = wikiApi.get();
        if (api.isEmpty()) return null;
        String current = effectiveVersion.get().orElse(null);
        if (current == null) return null;
        if (!current.equals(version)) reset(current);
        String cached = cache.get(itemId);
        if (cached != null) return cached;
        Optional<String> textureKey = textureKeys.computeIfAbsent(itemId,
                id -> api.get().getItem(current, id).map(McItemEntry::textureKey).filter(key -> !key.isBlank()));
        if (textureKey.isEmpty()) return null;
        String key = textureKey.get();
        int slash = key.indexOf('/');
        if (slash <= 0) return null;
        String kind = key.substring(0, slash);
        String path = key.substring(slash + 1);
        return api.get().getTexture(current, kind, path)
                .map(bytes -> {
                    String uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
                    cache.putIfAbsent(itemId, uri);
                    return uri;
                })
                .orElse(null);
    }

    private synchronized void reset(String current) {
        if (current.equals(version)) return;
        version = current;
        cache.clear();
        textureKeys.clear();
    }
}
