package online.yudream.base.plugin.mcguess.application;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import online.yudream.base.plugin.mcguess.application.dto.McguessSettingsView;
import online.yudream.base.plugin.mcguess.domain.McguessSettingsRepository;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;

/**
 * 插件设置用例：游戏数据版本（gameVersion）的读取与保存。
 * 空 = 跟随 mc-wiki 默认发布版本；钉住的版本被取消发布时自动降级回默认并在视图中以 pinnedMissing 提示。
 * 目录（WikiCatalogSource）与图标（IconSupport）经 {@link #effectiveVersion()} 消费同一生效版本，
 * 避免出现一个版本的配方配另一个版本的图标。
 */
public final class McguessSettingsService {

    private final Supplier<Optional<McWikiApi>> wikiApi;
    private final McguessSettingsRepository settings;

    public McguessSettingsService(Supplier<Optional<McWikiApi>> wikiApi, McguessSettingsRepository settings) {
        this.wikiApi = wikiApi;
        this.settings = settings;
    }

    /** 生效数据版本：钉住版本仍在已发布列表时用之，否则回退 mc-wiki 默认；provider 不可用或从未发布时为空。 */
    public Optional<String> effectiveVersion() {
        Optional<McWikiApi> api = wikiApi.get();
        if (api.isEmpty()) {
            return Optional.empty();
        }
        String pinned = settings.gameVersion();
        if (!pinned.isBlank() && api.get().publishedVersions().contains(pinned)) {
            return Optional.of(pinned);
        }
        return api.get().publishedVersion();
    }

    public McguessSettingsView view() {
        Optional<McWikiApi> api = wikiApi.get();
        List<String> published = api.map(McWikiApi::publishedVersions).orElse(List.of());
        String pinned = settings.gameVersion();
        String defaultVersion = api.flatMap(McWikiApi::publishedVersion).orElse(null);
        boolean pinnedMissing = !pinned.isBlank() && !published.contains(pinned);
        String effective = !pinned.isBlank() && !pinnedMissing ? pinned : defaultVersion;
        return new McguessSettingsView(pinned, effective, defaultVersion, published, pinnedMissing, api.isPresent());
    }

    /** 保存钉住版本；空串表示清除钉住、跟随默认。版本必须已在 mc-wiki 发布，拒绝手输的未发布版本。 */
    public McguessSettingsView save(String gameVersion) {
        String pinned = gameVersion == null ? "" : gameVersion.trim();
        if (pinned.isBlank()) {
            settings.saveGameVersion("");
            return view();
        }
        Optional<McWikiApi> api = wikiApi.get();
        if (api.isEmpty()) {
            throw new IllegalStateException("mc-wiki 暂不可用，无法校验版本是否已发布，请稍后再试");
        }
        if (!api.get().publishedVersions().contains(pinned)) {
            throw new IllegalArgumentException("版本 " + pinned + " 未在 mc-wiki 发布，请从列表中选择已发布版本");
        }
        settings.saveGameVersion(pinned);
        return view();
    }
}
