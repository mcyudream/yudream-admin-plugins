package online.yudream.base.plugin.shop.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.aggregate.ShopVariant;
import online.yudream.base.plugin.shop.domain.enumerate.ShopProductStatus;
import org.junit.jupiter.api.Test;

/**
 * 商品型号（同一商品可选不同型号）：价格与库存以型号为准、扣减只落到被选中的型号。
 */
class ShopVariantTest {

    private static final String OWNER = "2001";

    @Test
    void variantsDriveProductPriceAndStock() {
        ShopProduct product = productWith(variant("西瓜", "1000", 3), variant("钻石", "1500", 2));

        assertTrue(product.hasVariants());
        assertEquals(new BigDecimal("1000"), product.price(), "商品价取型号最低价");
        assertEquals(5, product.stock(), "商品库存为型号合计");
        assertEquals(ShopProductStatus.ON_SHELF, product.status());
    }

    @Test
    void unlimitedVariantMakesProductStockUnlimited() {
        assertEquals(ShopProduct.UNLIMITED_STOCK, productWith(variant("西瓜", "1000", 3), variant("钻石", "1500", -1)).stock());
    }

    @Test
    void purchaseOnlyConsumesSelectedVariant() {
        ShopProduct product = productWith(variant("西瓜", "1000", 3), variant("钻石", "1500", 2));
        String diamond = product.variants().get(1).id();

        ShopProduct afterBuy = product.purchased(2, diamond);

        assertEquals(0, afterBuy.variant(diamond).orElseThrow().stock(), "选中的型号被扣减");
        assertEquals(3, afterBuy.variant(product.variants().get(0).id()).orElseThrow().stock(), "其它型号不受影响");
        assertEquals(3, afterBuy.stock(), "商品库存跟着变为型号合计");
        assertEquals(2L, afterBuy.soldCount());
        assertTrue(afterBuy.variant(diamond).orElseThrow().soldOut());
    }

    @Test
    void purchaseRequiresVariantAndRejectsUnknownOrInsufficient() {
        ShopProduct product = productWith(variant("西瓜", "1000", 3), variant("钻石", "1500", 1));
        String diamond = product.variants().get(1).id();

        assertEquals("请选择商品型号", assertThrows(IllegalArgumentException.class,
                () -> product.purchased(1, null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> product.purchased(1, "not-exist"));
        assertThrows(IllegalArgumentException.class, () -> product.purchased(2, diamond), "库存不足");
    }

    @Test
    void rollbackRestoresSelectedVariant() {
        ShopProduct product = productWith(variant("西瓜", "1000", 3), variant("钻石", "1500", 2));
        String diamond = product.variants().get(1).id();

        ShopProduct restored = product.purchased(2, diamond).purchaseRolledBack(2, diamond);

        assertEquals(2, restored.variant(diamond).orElseThrow().stock());
        assertEquals(5, restored.stock());
        assertEquals(0L, restored.soldCount());
    }

    @Test
    void variantValidationRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> ShopVariant.create("  ", new BigDecimal("1"), 1, null));
        assertThrows(IllegalArgumentException.class, () -> ShopVariant.create("西瓜", BigDecimal.ZERO, 1, null));
        assertThrows(IllegalArgumentException.class, () -> ShopVariant.create("西瓜", new BigDecimal("1"), -2, null));
        assertEquals("西瓜", ShopVariant.create(" 西瓜 ", new BigDecimal("1"), 1, " ").name(), "型号名去空白后保存");
        assertFalse(ShopVariant.create("西瓜", new BigDecimal("1"), 0, null).unlimitedStock());
    }

    private static ShopProduct productWith(ShopVariant... variants) {
        return ShopProduct.create(OWNER, "冰箱贴", null, "", List.of(), "POINT", new BigDecimal("1000"), -1, 0,
                List.of(variants), "GENERIC", Map.of());
    }

    private static ShopVariant variant(String name, String price, int stock) {
        return ShopVariant.create(name, new BigDecimal(price), stock, null);
    }
}
