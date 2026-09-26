package online.yudream.base.plugin.shop.domain;

import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 结算方式解析：历史文档或脏数据里的未知值必须回落到 SELLER（卖家收款的默认语义），
 * 不能因为一个未知字符串就把订单判成消耗式。
 */
class ShopSettlementTest {

    @Test
    void unknownValueFallsBackToSeller() {
        assertEquals(ShopSettlement.SELLER, ShopSettlement.from("bogus"));
        assertEquals(ShopSettlement.SELLER, ShopSettlement.from(null));
        assertEquals(ShopSettlement.SELLER, ShopSettlement.from(""));
        assertEquals(ShopSettlement.SELLER, ShopSettlement.from("   "));
    }

    @Test
    void knownValuesAreCaseInsensitiveAndTrimmed() {
        assertEquals(ShopSettlement.SELLER, ShopSettlement.from("seller"));
        assertEquals(ShopSettlement.SELLER, ShopSettlement.from(" SELLER "));
        assertEquals(ShopSettlement.BURN, ShopSettlement.from("burn"));
        assertEquals(ShopSettlement.BURN, ShopSettlement.from(" BURN "));
    }

    @Test
    void labelsDescribeWhereTheMoneyGoes() {
        assertEquals("转给卖家", ShopSettlement.SELLER.label());
        assertEquals("消耗", ShopSettlement.BURN.label());
    }
}
