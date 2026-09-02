package online.yudream.base.plugin.mcguess.infrastructure;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;

/**
 * 懒加载物品目录源：首次访问或生效数据版本变化时经 {@link McAssetsSnapshot} 重建目录
 * （物品、配方各一次全量清单调用）；provider 暂缺且已有旧目录时继续服务旧目录，
 * 保证 mc-wiki 热重载 / 重发布间隙对局不中断。
 * 生效版本由设置服务解析（钉住版本优先，否则 mc-wiki 默认发布版本），与图标供给保持一致。
 */
public final class WikiCatalogSource implements Supplier<McCatalog> {
    private final Supplier<Optional<McWikiApi>> wikiApi;
    private final Supplier<Optional<String>> effectiveVersion;
    private volatile McCatalog catalog;
    private volatile String loadedVersion;

    public WikiCatalogSource(Supplier<Optional<McWikiApi>> wikiApi, Supplier<Optional<String>> effectiveVersion) {
        this.wikiApi = wikiApi;
        this.effectiveVersion = effectiveVersion;
    }

    @Override
    public McCatalog get() {
        McCatalog current = catalog;
        if (current != null && Objects.equals(loadedVersion, currentVersion())) return current;
        synchronized (this) {
            String version = currentVersion();
            if (catalog != null && Objects.equals(loadedVersion, version)) return catalog;
            Optional<McWikiApi> api = wikiApi.get();
            if (version == null || api.isEmpty()) {
                if (catalog != null) return catalog;
                throw new IllegalStateException("mc-wiki 尚未发布资源版本，请先在 mc-wiki 管理端导入并发布一个版本");
            }
            catalog = McAssetsSnapshot.load(api.get(), version).catalog();
            loadedVersion = version;
            return catalog;
        }
    }

    private String currentVersion() { return effectiveVersion.get().orElse(null); }
}
