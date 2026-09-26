package online.yudream.base.plugin.shop.application.cmd;

import java.math.BigDecimal;

/**
 * 型号保存入参。id 为空表示新建型号（由领域生成 id）；stock 为 null 时按不限库存处理。
 */
public record ShopVariantCmd(String id, String name, BigDecimal price, Integer stock, String image) {

    public static final int UNLIMITED_STOCK = -1;

    public int stockOrDefault() {
        return stock == null ? UNLIMITED_STOCK : stock;
    }
}
