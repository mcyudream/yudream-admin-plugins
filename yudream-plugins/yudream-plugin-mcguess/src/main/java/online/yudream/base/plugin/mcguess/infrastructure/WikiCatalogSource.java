package online.yudream.base.plugin.mcguess.infrastructure;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;

/**
 * 懒加载物品目录源：首次访问、生效数据版本变化或共享渲染资产覆盖从空变为可用时
 * 经 {@link McAssetsSnapshot} 重建目录（物品、配方各一次全量清单调用）。
 * provider 暂缺且已有旧目录时继续服务旧目录，保证 mc-wiki 热重载 / 重发布间隙对局不中断。
 * 生效版本由设置服务解析（钉住版本优先，否则 mc-wiki 默认发布版本），与图标供给保持一致。
 *
 * {@link McItem#icon()} 在快照时由 {@code hasRender} 冻结。云端常见顺序是先发布 wiki、
 * 后一键更新渲染资产：若只按版本缓存，迷雾/宾果会一直对着空图标池开局。
 */
public final class WikiCatalogSource implements Supplier<McCatalog> {
    private final Supplier<Optional<McWikiApi>> wikiApi;
    private final Supplier<Optional<String>> effectiveVersion;
    private volatile McCatalog catalog;
    private volatile String loadedVersion;
    private volatile boolean loadedHasRenders;

    public WikiCatalogSource(Supplier<Optional<McWikiApi>> wikiApi, Supplier<Optional<String>> effectiveVersion) {
        this.wikiApi = wikiApi;
        this.effectiveVersion = effectiveVersion;
    }

    @Override
    public McCatalog get() {
        McCatalog current = catalog;
        String version = currentVersion();
        if (current != null && Objects.equals(loadedVersion, version) && !needsRenderRefresh(current, version)) {
            return current;
        }
        synchronized (this) {
            version = currentVersion();
            if (catalog != null && Objects.equals(loadedVersion, version) && !needsRenderRefresh(catalog, version)) {
                return catalog;
            }
            Optional<McWikiApi> api = wikiApi.get();
            if (version == null || api.isEmpty()) {
                if (catalog != null) return catalog;
                throw new IllegalStateException("mc-wiki 尚未发布资源版本，请先在 mc-wiki 管理端导入并发布一个版本");
            }
            McCatalog rebuilt = McAssetsSnapshot.load(api.get(), version).catalog(api.get());
            catalog = rebuilt;
            loadedVersion = version;
            loadedHasRenders = !rebuilt.iconItems().isEmpty();
            return rebuilt;
        }
    }

    /**
     * 目录按版本缓存时，若上次快照没有任何带图标物品、而当前渲染资产已覆盖至少一个物品，
     * 必须重建，否则迷雾/宾果会一直对着空 {@code iconItems} 开局。
     * 已有图标的快照不因个别物品增减反复全量重建。
     */
    private boolean needsRenderRefresh(McCatalog current, String version) {
        if (loadedHasRenders || current == null || version == null) {
            return false;
        }
        Optional<McWikiApi> api = wikiApi.get();
        if (api.isEmpty() || current.items().isEmpty()) {
            return false;
        }
        McWikiApi service = api.get();
        for (McItem item : current.items()) {
            if (service.hasRender(item.id(), "item") || service.hasRender(item.id(), "block")) {
                return true;
            }
        }
        return false;
    }

    private String currentVersion() { return effectiveVersion.get().orElse(null); }
}
