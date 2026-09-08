package online.yudream.base.plugin.mcpet.infrastructure.repository;

import online.yudream.base.plugin.mcpet.domain.aggregate.PetPreference;
import online.yudream.base.plugin.mcpet.domain.valobj.PetDefaults;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class McPetRepository {

    private static final String OPTIONS = "options";
    private static final String PREFERENCES = "preferences";
    private static final String DEFAULTS_ID = "defaults";

    private final PluginDocumentStore documents;

    public McPetRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public PetDefaults defaults() {
        return documents.findById(OPTIONS, DEFAULTS_ID)
                .map(this::toDefaults)
                .orElseGet(PetDefaults::builtin);
    }

    public PetDefaults saveDefaults(PetDefaults defaults) {
        PetDefaults normalized = defaults.normalized();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("mode", normalized.mode());
        putIfNotNull(document, "playerName", normalized.playerName());
        putIfNotNull(document, "textureHash", normalized.textureHash());
        putIfNotNull(document, "model", normalized.model());
        document.put("animation", normalized.animation());
        document.put("clickAction", normalized.clickAction());
        document.put("size", normalized.size());
        document.put("corner", normalized.corner());
        return toDefaults(documents.save(OPTIONS, DEFAULTS_ID, document));
    }

    public Optional<PetPreference> findPreference(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(PREFERENCES, userId.trim()).map(this::toPreference);
    }

    public PetPreference savePreference(PetPreference preference) {
        PetPreference normalized = preference.normalized();
        return toPreference(documents.save(PREFERENCES, normalized.userId(), preferenceDocument(normalized)));
    }

    public void deletePreference(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        documents.delete(PREFERENCES, userId.trim());
    }

    public List<PetPreference> listPreferences(int page, int size) {
        return documents.findAll(PREFERENCES, page, size).stream().map(this::toPreference).toList();
    }

    public long preferenceCount() {
        return documents.count(PREFERENCES);
    }

    private Map<String, Object> preferenceDocument(PetPreference preference) {
        Map<String, Object> document = new LinkedHashMap<>();
        putIfNotNull(document, "userId", preference.userId());
        putIfNotNull(document, "mode", preference.mode());
        putIfNotNull(document, "playerName", preference.playerName());
        putIfNotNull(document, "closetItemId", preference.closetItemId());
        putIfNotNull(document, "size", preference.size());
        putIfNotNull(document, "corner", preference.corner());
        putIfNotNull(document, "positionX", preference.positionX());
        putIfNotNull(document, "positionY", preference.positionY());
        document.put("hidden", preference.hidden());
        putIfNotNull(document, "updatedAt", preference.updatedAt());
        return document;
    }

    private void putIfNotNull(Map<String, Object> document, String key, Object value) {
        // 沙盒文档存储遇 null 直接 NPE，写入前统一剔除
        if (value != null) {
            document.put(key, value);
        }
    }

    private PetDefaults toDefaults(Map<String, Object> document) {
        return new PetDefaults(
                stringValue(document.get("mode")),
                stringValue(document.get("playerName")),
                stringValue(document.get("textureHash")),
                stringValue(document.get("model")),
                boolValue(document.get("animation"), true),
                stringValue(document.get("clickAction")),
                intValue(document.get("size"), 120),
                stringValue(document.get("corner"))
        ).normalized();
    }

    private PetPreference toPreference(Map<String, Object> document) {
        return new PetPreference(
                stringValue(document.get("userId")),
                stringValue(document.get("mode")),
                stringValue(document.get("playerName")),
                stringValue(document.get("closetItemId")),
                document.get("size") == null ? null : intValue(document.get("size"), 0),
                stringValue(document.get("corner")),
                doubleValue(document.get("positionX")),
                doubleValue(document.get("positionY")),
                boolValue(document.get("hidden"), false),
                longValue(document.get("updatedAt"))
        ).normalized();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean boolValue(Object value, boolean defaultValue) {
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private int intValue(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
