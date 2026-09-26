package online.yudream.base.plugin.shop.domain;

import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.enumerate.ShopProductStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品聚合的每人限购语义。
 *
 * <p>限购是积分兑换（POINTS_REDEEM）控制「每人限兑」的唯一手段，因此边界必须明确：
 * 0 表示不限、恰好到限允许、超出即拒绝，且编辑、购买、回滚、上下架都不能丢掉该配置。
 */
class ShopProductTest {

    private static final String OWNER = "2001";

    private ShopProduct product(int perUserLimit) {
        return ShopProduct.create(OWNER, "称号兑换券", "", "", List.of(), "POINT", new BigDecimal("100"), 5,
                perUserLimit, "POINTS_REDEEM", Map.of());
    }

    @Test
    void zeroLimitMeansUnlimited() {
        ShopProduct item = product(0);

        assertFalse(item.hasPerUserLimit());
        assertFalse(item.exceedsPerUserLimit(0, 99));
        assertFalse(item.exceedsPerUserLimit(1000, 5));
        assertEquals(ShopProduct.UNLIMITED_PER_USER, item.perUserLimit());
    }

    @Test
    void limitAllowsExactlyTheConfiguredQuantity() {
        ShopProduct item = product(3);

        assertTrue(item.hasPerUserLimit());
        assertFalse(item.exceedsPerUserLimit(0, 3));
        assertFalse(item.exceedsPerUserLimit(2, 1));
        assertTrue(item.exceedsPerUserLimit(2, 2));
        assertTrue(item.exceedsPerUserLimit(3, 1));
        assertTrue(item.exceedsPerUserLimit(0, 4));
    }

    @Test
    void negativeLimitIsRejected() {
        assertEquals("每人限购不能为负，不限请填 0", assertThrows(IllegalArgumentException.class,
                () -> product(-1)).getMessage());
        assertEquals("每人限购不能为负，不限请填 0", assertThrows(IllegalArgumentException.class,
                () -> product(0).update("新称号", "", "", List.of(), "POINT", new BigDecimal("200"), 5, -2,
                        "POINTS_REDEEM", Map.of())).getMessage());
    }

    @Test
    void updateReplacesTheLimitAndKeepsTheIdentity() {
        ShopProduct item = product(2);

        ShopProduct updated = item.update("新称号", "", "", List.of(), "POINT", new BigDecimal("200"), 9, 4,
                "POINTS_REDEEM", Map.of());

        assertEquals(item.id(), updated.id());
        assertEquals(OWNER, updated.ownerId());
        assertEquals(4, updated.perUserLimit());
        assertTrue(updated.hasPerUserLimit());
        assertEquals(9, updated.stock());
        assertTrue(updated.exceedsPerUserLimit(3, 2));
    }

    @Test
    void limitSurvivesPurchaseRollbackAndShelfChanges() {
        ShopProduct item = product(4);

        assertEquals(4, item.purchased(2).perUserLimit());
        assertEquals(4, item.purchased(2).purchaseRolledBack(2).perUserLimit());
        assertEquals(4, item.shelf(ShopProductStatus.OFF_SHELF).perUserLimit());
        assertEquals(3, item.purchased(2).stock());
        assertEquals(5, item.purchased(2).purchaseRolledBack(2).stock());
    }

    @Test
    void creatingWithoutLimitLeavesItUnlimited() {
        ShopProduct item = ShopProduct.create(OWNER, "普通商品", "", "", List.of(), "CNY", new BigDecimal("1"),
                5, "GENERIC", Map.of());

        assertEquals(0, item.perUserLimit());
        assertFalse(item.hasPerUserLimit());
        assertFalse(item.exceedsPerUserLimit(100, 99));
    }
}
