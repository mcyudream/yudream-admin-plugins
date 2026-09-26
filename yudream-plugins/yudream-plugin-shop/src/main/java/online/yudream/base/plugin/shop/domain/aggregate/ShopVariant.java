package online.yudream.base.plugin.shop.domain.aggregate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 商品型号（规格）：同一商品下可选的差异化选项，各自带价格、库存与可选图片。
 *
 * <p>对应「像电商那样同一商品选不同型号」：型号为空表示商品没有型号，按商品自身的价格与库存售卖；
 * 一旦配置了型号，价格与库存就以型号为准——商品自身的 price 取型号最低价、stock 取型号库存合计，
 * 保证列表排序、筛选与展示仍然一致（见 {@link ShopProduct} 的构造约定）。
 *
 * @param id    型号 id（商品内唯一）
 * @param name  型号名（如「西瓜」「钻石」「金条」）
 * @param price 该型号单价
 * @param stock 该型号库存，{@link #UNLIMITED_STOCK} 表示不限
 * @param image 该型号可选图片（{@code /api/files/} 地址）
 */
public record ShopVariant(String id, String name, BigDecimal price, int stock, String image) {

    public static final int UNLIMITED_STOCK = -1;
    /** 单个商品的型号数量上限。 */
    public static final int MAX_VARIANTS = 20;
    /** 型号名称长度上限。 */
    public static final int MAX_NAME_LENGTH = 40;

    public ShopVariant {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("型号 id 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("型号名称不能为空");
        }
        name = name.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("型号名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("型号「" + name + "」的价格必须大于 0");
        }
        if (stock < UNLIMITED_STOCK) {
            throw new IllegalArgumentException("型号「" + name + "」的库存不能小于 0，不限库存请填 -1");
        }
        image = image == null || image.isBlank() ? null : image.trim();
    }

    /** 新建型号：id 由领域生成。 */
    public static ShopVariant create(String name, BigDecimal price, int stock, String image) {
        return new ShopVariant(UUID.randomUUID().toString().replace("-", ""), name, price, stock, image);
    }

    public boolean unlimitedStock() {
        return stock == UNLIMITED_STOCK;
    }

    public boolean soldOut() {
        return stock == 0;
    }

    /** 扣减该型号库存；不足时抛异常。 */
    ShopVariant purchased(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("购买数量必须大于 0");
        }
        if (unlimitedStock()) {
            return this;
        }
        if (stock < quantity) {
            throw new IllegalArgumentException("型号「" + name + "」库存不足");
        }
        return new ShopVariant(id, name, price, stock - quantity, image);
    }

    /** 取消/退款回滚该型号库存。 */
    ShopVariant rolledBack(int quantity) {
        if (unlimitedStock()) {
            return this;
        }
        return new ShopVariant(id, name, price, stock + quantity, image);
    }
}
