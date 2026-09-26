package online.yudream.base.plugin.shop.domain.aggregate;

import online.yudream.base.plugin.shop.domain.enumerate.ShopProductStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商品聚合。stock 为 -1 表示不限库存。
 */
public record ShopProduct(String id, String ownerId, String title, String summary, String descriptionMd,
                          List<String> images, String assetCode, BigDecimal price, int stock, long soldCount,
                          String type, Map<String, Object> typeConfig, ShopProductStatus status,
                          long createdAt, long updatedAt) {

    public static final int UNLIMITED_STOCK = -1;

    public static ShopProduct create(String ownerId, String title, String summary, String descriptionMd,
                                     List<String> images, String assetCode, BigDecimal price, int stock,
                                     String type, Map<String, Object> typeConfig) {
        validate(title, price, stock);
        long now = System.currentTimeMillis();
        return new ShopProduct(UUID.randomUUID().toString().replace("-", ""), ownerId, title.trim(),
                trimToNull(summary), descriptionMd == null ? "" : descriptionMd, List.copyOf(images),
                assetCode, price, stock, 0L, type, typeConfig == null ? Map.of() : Map.copyOf(typeConfig),
                ShopProductStatus.ON_SHELF, now, now);
    }

    public ShopProduct update(String title, String summary, String descriptionMd, List<String> images,
                              String assetCode, BigDecimal price, int stock, String type,
                              Map<String, Object> typeConfig) {
        validate(title, price, stock);
        // 编辑不能把库存改到比已售还少（不限库存除外）
        if (stock != UNLIMITED_STOCK && stock < 0) {
            throw new IllegalArgumentException("库存不能小于 0");
        }
        return new ShopProduct(id, ownerId, title.trim(), trimToNull(summary),
                descriptionMd == null ? "" : descriptionMd, List.copyOf(images), assetCode, price, stock,
                soldCount, type, typeConfig == null ? Map.of() : Map.copyOf(typeConfig), status,
                createdAt, System.currentTimeMillis());
    }

    /** 扣减库存并累计销量；库存不足抛异常。 */
    public ShopProduct purchased(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("购买数量必须大于 0");
        }
        int nextStock = stock;
        if (stock != UNLIMITED_STOCK) {
            if (stock < quantity) {
                throw new IllegalArgumentException("商品库存不足");
            }
            nextStock = stock - quantity;
        }
        return new ShopProduct(id, ownerId, title, summary, descriptionMd, images, assetCode, price,
                nextStock, soldCount + quantity, type, typeConfig, status, createdAt,
                System.currentTimeMillis());
    }

    /** 取消购买回滚库存与销量（支付失败时调用）。 */
    public ShopProduct purchaseRolledBack(int quantity) {
        int nextStock = stock == UNLIMITED_STOCK ? stock : stock + quantity;
        return new ShopProduct(id, ownerId, title, summary, descriptionMd, images, assetCode, price,
                nextStock, Math.max(0L, soldCount - quantity), type, typeConfig, status, createdAt,
                System.currentTimeMillis());
    }

    public ShopProduct shelf(ShopProductStatus next) {
        return new ShopProduct(id, ownerId, title, summary, descriptionMd, images, assetCode, price,
                stock, soldCount, type, typeConfig, next, createdAt, System.currentTimeMillis());
    }

    public boolean onShelf() {
        return status == ShopProductStatus.ON_SHELF;
    }

    public String coverImage() {
        return images == null || images.isEmpty() ? null : images.get(0);
    }

    private static void validate(String title, BigDecimal price, int stock) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("商品标题不能为空");
        }
        if (title.trim().length() > 60) {
            throw new IllegalArgumentException("商品标题不能超过 60 个字符");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("商品价格必须大于 0");
        }
        if (stock < UNLIMITED_STOCK) {
            throw new IllegalArgumentException("库存不能小于 0，不限库存请填 -1");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
