package online.yudream.base.plugin.mcguess.infrastructure.repository;

import java.util.LinkedHashMap;
import java.util.Map;
import online.yudream.base.plugin.mcguess.domain.McguessSettingsRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

/** 插件设置存储：单文档 options/config，当前仅 gameVersion（钉住的 MC 数据版本，空 = 跟随 mc-wiki 默认）。 */
public final class McguessDocumentSettingsRepository implements McguessSettingsRepository {
    private static final String COLLECTION = "options";
    private static final String ID = "config";
    private final PluginDocumentStore documents;

    public McguessDocumentSettingsRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    /** 钉住的数据版本；未设置或空串表示跟随 mc-wiki 默认发布版本。 */
    public String gameVersion() {
        return documents.findById(COLLECTION, ID)
                .map(m -> String.valueOf(m.getOrDefault("gameVersion", "")))
                .orElse("");
    }

    /** 保存钉住版本；空串清除设置（恢复跟随默认）。写入基于现有文档合并，避免覆盖将来新增的设置项。 */
    public void saveGameVersion(String version) {
        Map<String, Object> doc = new LinkedHashMap<>(documents.findById(COLLECTION, ID).orElse(Map.of()));
        if (version == null || version.isBlank()) {
            doc.remove("gameVersion");
        } else {
            doc.put("gameVersion", version);
        }
        documents.save(COLLECTION, ID, doc);
    }
}
