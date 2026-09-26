package online.yudream.base.plugin.shop.infrastructure.repository;

import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.enumerate.ShopProductStatus;
import online.yudream.base.plugin.shop.infrastructure.support.DocumentSupport;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ShopProductRepository {

    private static final int SCAN_PAGE_SIZE = 200;
    private static final String PRODUCTS = "products";

    private final PluginDocumentStore documents;

    public ShopProductRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public ShopProduct save(ShopProduct product) {
        return toProduct(documents.save(PRODUCTS, product.id(), DocumentSupport.stripNulls(productDocument(product))));
    }

    public Optional<ShopProduct> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(PRODUCTS, id.trim()).map(this::toProduct);
    }

    public void delete(String id) {
        documents.delete(PRODUCTS, id);
    }

    /** 全量扫描（文档存储无排序/复合查询，过滤与排序在应用层内存完成）。 */
    public List<ShopProduct> findAll() {
        List<ShopProduct> records = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(PRODUCTS, page, SCAN_PAGE_SIZE);
            batch.forEach(document -> records.add(toProduct(document)));
            if (batch.size() < SCAN_PAGE_SIZE) {
                return records;
            }
            page++;
        }
    }

    private Map<String, Object> productDocument(ShopProduct product) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", product.id());
        document.put("ownerId", product.ownerId());
        document.put("title", product.title());
        document.put("summary", product.summary());
        document.put("descriptionMd", product.descriptionMd());
        document.put("images", product.images() == null ? List.of() : new ArrayList<>(product.images()));
        document.put("assetCode", product.assetCode());
        document.put("price", product.price() == null ? null : product.price().toPlainString());
        document.put("stock", product.stock());
        document.put("soldCount", product.soldCount());
        document.put("type", product.type());
        document.put("typeConfig", product.typeConfig() == null ? Map.of() : new LinkedHashMap<>(product.typeConfig()));
        document.put("status", product.status().name());
        document.put("createdAt", product.createdAt());
        document.put("updatedAt", product.updatedAt());
        return document;
    }

    @SuppressWarnings("unchecked")
    private ShopProduct toProduct(Map<String, Object> document) {
        return new ShopProduct(
                stringValue(document.get("id")),
                stringValue(document.get("ownerId")),
                stringValue(document.get("title")),
                stringValue(document.get("summary")),
                stringValue(document.get("descriptionMd")),
                document.get("images") instanceof List<?> images ? images.stream().map(String::valueOf).toList() : List.of(),
                stringValue(document.get("assetCode")),
                decimalValue(document.get("price")),
                intValue(document.get("stock"), ShopProduct.UNLIMITED_STOCK),
                longValue(document.get("soldCount"), 0L),
                stringValue(document.get("type")),
                document.get("typeConfig") instanceof Map<?, ?> config ? (Map<String, Object>) config : Map.of(),
                parseStatus(document.get("status")),
                longValue(document.get("createdAt"), 0L),
                longValue(document.get("updatedAt"), 0L)
        );
    }

    private ShopProductStatus parseStatus(Object value) {
        try {
            return value == null ? ShopProductStatus.OFF_SHELF : ShopProductStatus.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return ShopProductStatus.OFF_SHELF;
        }
    }

    static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static BigDecimal decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    static long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    static Long boxedLongValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
