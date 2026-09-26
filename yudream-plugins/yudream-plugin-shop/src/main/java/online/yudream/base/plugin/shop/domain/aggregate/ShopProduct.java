package online.yudream.base.plugin.shop.domain.aggregate;

import online.yudream.base.plugin.shop.domain.enumerate.ShopProductStatus;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 商品聚合。stock 为 -1 表示不限库存；perUserLimit 为 0 表示不限每人购买件数
 * （积分兑换类商品常按「每人限兑」控制，判定在应用层，因为需要历史订单）。
 *
 * <p>variants 为空表示商品没有型号，按商品自身的价格与库存售卖；非空表示「同一商品可选不同型号」，
 * 此时单价取型号最低价、库存取型号合计（任一型号不限库存则视为不限），
 * 具体扣减落到被选中的型号上——由构造约定保证商品自身字段与型号始终一致。
 */
public record ShopProduct(String id, String ownerId, String title, String summary, String descriptionMd,
                          List<String> images, String assetCode, BigDecimal price, int stock, int perUserLimit,
                          List<ShopVariant> variants, long soldCount, String type, Map<String, Object> typeConfig,
                          ShopProductStatus status, long createdAt, long updatedAt, int sortOrder) {

    public static final int UNLIMITED_STOCK = -1;
    /** 不限每人购买件数。 */
    public static final int UNLIMITED_PER_USER = 0;
    /** 排序权重默认值：0 表示只按创建时间倒序（玩家商品即如此）。 */
    public static final int DEFAULT_SORT_ORDER = 0;
    /** 排序权重上限，避免误输入把商品顶到看不见的位置。 */
    public static final int MAX_SORT_ORDER = 9999;
    /**
     * 平台归属标记：官方（消耗式结算，如积分兑换）商品没有归属用户，统一记为该值。
     * 对应的用户商品必须归属真实用户，二者由应用层按商品类型的结算方式绑定。
     */
    public static final String PLATFORM_OWNER = "system";

    public ShopProduct {
        images = images == null ? List.of() : List.copyOf(images);
        variants = variants == null ? List.of() : List.copyOf(variants);
        typeConfig = typeConfig == null ? Map.of() : Map.copyOf(typeConfig);
        if (sortOrder < 0 || sortOrder > MAX_SORT_ORDER) {
            throw new IllegalArgumentException("排序值必须在 0 到 " + MAX_SORT_ORDER + " 之间");
        }
        if (!variants.isEmpty()) {
            // 有型号时价格与库存以型号为准，避免出现商品价与型号价互相矛盾的文档
            price = variants.stream().map(ShopVariant::price).min(Comparator.naturalOrder()).orElse(price);
            stock = totalStock(variants);
        }
    }

    public static ShopProduct create(String ownerId, String title, String summary, String descriptionMd,
                                     List<String> images, String assetCode, BigDecimal price, int stock,
                                     String type, Map<String, Object> typeConfig) {
        return create(ownerId, title, summary, descriptionMd, images, assetCode, price, stock, UNLIMITED_PER_USER,
                List.of(), type, typeConfig);
    }

    public static ShopProduct create(String ownerId, String title, String summary, String descriptionMd,
                                     List<String> images, String assetCode, BigDecimal price, int stock,
                                     int perUserLimit, String type, Map<String, Object> typeConfig) {
        return create(ownerId, title, summary, descriptionMd, images, assetCode, price, stock, perUserLimit,
                List.of(), type, typeConfig);
    }

    public static ShopProduct create(String ownerId, String title, String summary, String descriptionMd,
                                     List<String> images, String assetCode, BigDecimal price, int stock,
                                     int perUserLimit, List<ShopVariant> variants, String type,
                                     Map<String, Object> typeConfig) {
        return create(ownerId, title, summary, descriptionMd, images, assetCode, price, stock, perUserLimit,
                variants, type, typeConfig, DEFAULT_SORT_ORDER);
    }

    public static ShopProduct create(String ownerId, String title, String summary, String descriptionMd,
                                     List<String> images, String assetCode, BigDecimal price, int stock,
                                     int perUserLimit, List<ShopVariant> variants, String type,
                                     Map<String, Object> typeConfig, int sortOrder) {
        validate(title, price, stock, perUserLimit);
        long now = System.currentTimeMillis();
        return new ShopProduct(UUID.randomUUID().toString().replace("-", ""), ownerId, title.trim(),
                trimToNull(summary), descriptionMd == null ? "" : descriptionMd, images,
                assetCode, price, stock, perUserLimit, variants, 0L, type, typeConfig,
                ShopProductStatus.ON_SHELF, now, now, sortOrder);
    }

    public ShopProduct update(String title, String summary, String descriptionMd, List<String> images,
                              String assetCode, BigDecimal price, int stock, String type,
                              Map<String, Object> typeConfig) {
        return update(title, summary, descriptionMd, images, assetCode, price, stock, perUserLimit, variants, type,
                typeConfig);
    }

    public ShopProduct update(String title, String summary, String descriptionMd, List<String> images,
                              String assetCode, BigDecimal price, int stock, int perUserLimit, String type,
                              Map<String, Object> typeConfig) {
        return update(title, summary, descriptionMd, images, assetCode, price, stock, perUserLimit, variants, type,
                typeConfig);
    }

    public ShopProduct update(String title, String summary, String descriptionMd, List<String> images,
                              String assetCode, BigDecimal price, int stock, int perUserLimit,
                              List<ShopVariant> nextVariants, String type, Map<String, Object> typeConfig) {
        return update(title, summary, descriptionMd, images, assetCode, price, stock, perUserLimit, nextVariants,
                type, typeConfig, sortOrder);
    }

    public ShopProduct update(String title, String summary, String descriptionMd, List<String> images,
                              String assetCode, BigDecimal price, int stock, int perUserLimit,
                              List<ShopVariant> nextVariants, String type, Map<String, Object> typeConfig,
                              int nextSortOrder) {
        validate(title, price, stock, perUserLimit);
        return new ShopProduct(id, ownerId, title.trim(), trimToNull(summary),
                descriptionMd == null ? "" : descriptionMd, images, assetCode, price, stock,
                perUserLimit, nextVariants, soldCount, type, typeConfig, status,
                createdAt, System.currentTimeMillis(), nextSortOrder);
    }

    /** 单独调整排序权重（管理端排序用）。 */
    public ShopProduct withSortOrder(int nextSortOrder) {
        return new ShopProduct(id, ownerId, title, summary, descriptionMd, images, assetCode, price, stock,
                perUserLimit, variants, soldCount, type, typeConfig, status, createdAt,
                System.currentTimeMillis(), nextSortOrder);
    }

    /** 扣减库存并累计销量；有型号时必须指定型号，库存不足抛异常。 */
    public ShopProduct purchased(int quantity) {
        return purchased(quantity, null);
    }

    public ShopProduct purchased(int quantity, String variantId) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("购买数量必须大于 0");
        }
        if (hasVariants()) {
            return purchaseVariant(quantity, variantId);
        }
        int nextStock = stock;
        if (stock != UNLIMITED_STOCK) {
            if (stock < quantity) {
                throw new IllegalArgumentException("商品库存不足");
            }
            nextStock = stock - quantity;
        }
        return new ShopProduct(id, ownerId, title, summary, descriptionMd, images, assetCode, price,
                nextStock, perUserLimit, variants, soldCount + quantity, type, typeConfig, status, createdAt,
                System.currentTimeMillis(), sortOrder);
    }

    /** 取消购买回滚库存与销量（支付失败、取消或退款时调用）。 */
    public ShopProduct purchaseRolledBack(int quantity) {
        return purchaseRolledBack(quantity, null);
    }

    public ShopProduct purchaseRolledBack(int quantity, String variantId) {
        if (hasVariants()) {
            ShopVariant target = requireVariant(variantId);
            return withVariant(target.rolledBack(quantity), quantity, -quantity);
        }
        int nextStock = stock == UNLIMITED_STOCK ? stock : stock + quantity;
        return new ShopProduct(id, ownerId, title, summary, descriptionMd, images, assetCode, price,
                nextStock, perUserLimit, variants, Math.max(0L, soldCount - quantity), type, typeConfig, status,
                createdAt, System.currentTimeMillis(), sortOrder);
    }

    public ShopProduct shelf(ShopProductStatus next) {
        return new ShopProduct(id, ownerId, title, summary, descriptionMd, images, assetCode, price,
                stock, perUserLimit, variants, soldCount, type, typeConfig, next, createdAt,
                System.currentTimeMillis(), sortOrder);
    }

    public boolean onShelf() {
        return status == ShopProductStatus.ON_SHELF;
    }

    /** 是否为平台官方商品（没有归属用户）。 */
    public boolean platformOwned() {
        return PLATFORM_OWNER.equals(ownerId);
    }

    /** 是否配置了可选型号。 */
    public boolean hasVariants() {
        return !variants.isEmpty();
    }

    public Optional<ShopVariant> variant(String variantId) {
        if (variantId == null || variantId.isBlank()) {
            return Optional.empty();
        }
        return variants.stream().filter(variant -> variant.id().equals(variantId.trim())).findFirst();
    }

    public String coverImage() {
        return images == null || images.isEmpty() ? null : images.get(0);
    }

    /** 是否配置了每人限购。 */
    public boolean hasPerUserLimit() {
        return perUserLimit > UNLIMITED_PER_USER;
    }

    /** 该用户已买 quantity 件后是否超出每人限购；未配置限购时恒为 false。 */
    public boolean exceedsPerUserLimit(long alreadyBought, int quantity) {
        return hasPerUserLimit() && alreadyBought + quantity > perUserLimit;
    }

    private ShopProduct purchaseVariant(int quantity, String variantId) {
        ShopVariant target = requireVariant(variantId);
        return withVariant(target.purchased(quantity), quantity, quantity);
    }

    private ShopProduct withVariant(ShopVariant next, int quantity, int soldDelta) {
        List<ShopVariant> nextVariants = variants.stream()
                .map(variant -> variant.id().equals(next.id()) ? next : variant)
                .toList();
        return new ShopProduct(id, ownerId, title, summary, descriptionMd, images, assetCode, price,
                stock, perUserLimit, nextVariants, Math.max(0L, soldCount + soldDelta), type, typeConfig, status,
                createdAt, System.currentTimeMillis(), sortOrder);
    }

    private ShopVariant requireVariant(String variantId) {
        if (variantId == null || variantId.isBlank()) {
            throw new IllegalArgumentException("请选择商品型号");
        }
        return variant(variantId)
                .orElseThrow(() -> new IllegalArgumentException("商品型号不存在或已下架：" + variantId));
    }

    private static int totalStock(List<ShopVariant> variants) {
        int total = 0;
        for (ShopVariant variant : variants) {
            if (variant.unlimitedStock()) {
                return UNLIMITED_STOCK;
            }
            total += variant.stock();
        }
        return total;
    }

    private static void validate(String title, BigDecimal price, int stock, int perUserLimit) {
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
        if (perUserLimit < UNLIMITED_PER_USER) {
            throw new IllegalArgumentException("每人限购不能为负，不限请填 0");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
