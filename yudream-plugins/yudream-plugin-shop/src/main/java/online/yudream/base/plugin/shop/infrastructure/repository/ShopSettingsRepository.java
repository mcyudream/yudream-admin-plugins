package online.yudream.base.plugin.shop.infrastructure.repository;

import online.yudream.base.plugin.shop.domain.valobj.ShopSettings;
import online.yudream.base.plugin.shop.infrastructure.support.DocumentSupport;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 商店设置持久化：单文档（collection=settings, id=settings），读不到时回退默认值。 */
public class ShopSettingsRepository {

    private static final String SETTINGS = "settings";
    private static final String SETTINGS_ID = "settings";

    private final PluginDocumentStore documents;

    public ShopSettingsRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public ShopSettings find() {
        return documents.findById(SETTINGS, SETTINGS_ID)
                .map(this::toSettings)
                .orElse(ShopSettings.defaults());
    }

    public ShopSettings save(ShopSettings settings) {
        ShopSettings normalized = settings.normalized();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", SETTINGS_ID);
        document.put("allowUserPublish", normalized.allowUserPublish());
        document.put("publishAssetCode", normalized.publishAssetCode());
        document.put("publishMinBalance", normalized.publishMinBalance() == null
                ? null
                : normalized.publishMinBalance().toPlainString());
        document.put("allowedAssetCodes", normalized.allowedAssetCodes().isEmpty()
                ? null
                : new ArrayList<>(normalized.allowedAssetCodes()));
        return toSettings(documents.save(SETTINGS, SETTINGS_ID, DocumentSupport.stripNulls(document)));
    }

    private ShopSettings toSettings(Map<String, Object> document) {
        return new ShopSettings(
                parseBoolean(document.get("allowUserPublish"), true),
                ShopProductRepository.stringValue(document.get("publishAssetCode")),
                parseBalance(document.get("publishMinBalance")),
                parseCodes(document.get("allowedAssetCodes"))
        ).normalized();
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private BigDecimal parseBalance(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private List<String> parseCodes(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
