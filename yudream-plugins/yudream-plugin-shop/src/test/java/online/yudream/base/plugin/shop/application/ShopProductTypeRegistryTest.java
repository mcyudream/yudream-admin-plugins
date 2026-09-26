package online.yudream.base.plugin.shop.application;

import online.yudream.base.plugin.shop.api.PluginShopProductType;
import online.yudream.base.plugin.shop.api.ShopDeliveryContext;
import online.yudream.base.plugin.shop.api.ShopDeliveryResult;
import online.yudream.base.plugin.shop.api.ShopProductTypeHandler;
import online.yudream.base.plugin.shop.application.service.ShopProductTypeRegistry;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import online.yudream.base.plugin.shop.support.FakePluginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置商品类型契约：POINTS_REDEEM 必须以 builtin 类型出现在类型列表里、
 * 结算方式为消耗（BURN）、无自动发货（停在待发放），且只接受 deliveryNote 配置。
 */
class ShopProductTypeRegistryTest {

    private ShopProductTypeRegistry registry;

    @BeforeEach
    void setUp() {
        // 内置类型不依赖钱包与扩展，用降级上下文即可。
        registry = new ShopProductTypeRegistry(FakePluginContext.withoutWallet());
    }

    @Test
    void listsBothBuiltInTypesWithPointsRedeemSecond() {
        List<PluginShopProductType> types = registry.types();

        assertEquals(List.of("GENERIC", "POINTS_REDEEM"),
                types.stream().map(PluginShopProductType::type).toList());
        PluginShopProductType points = types.get(1);
        assertEquals("积分兑换", points.displayName());
        assertTrue(points.builtin());
        assertTrue(points.description().contains("支付即扣除积分、不转给卖家"));
        assertTrue(points.description().contains("每人限购"));
        assertTrue(types.get(0).builtin());
    }

    @Test
    void pointsRedeemBurnsBuyerAssetAndWaitsForTheSeller() {
        ShopProductTypeHandler points = registry.handler("points_redeem").orElseThrow();
        ShopProductTypeHandler generic = registry.handler("GENERIC").orElseThrow();

        assertEquals(ShopProductTypeRegistry.POINTS_REDEEM_TYPE, points.type());
        assertEquals(ShopSettlement.BURN, points.settlement());
        assertEquals(ShopSettlement.SELLER, generic.settlement());

        ShopDeliveryResult result = points.deliver(new ShopDeliveryContext("order-1", "product-1",
                ShopProductTypeRegistry.POINTS_REDEEM_TYPE, Map.of(), "1001", 1));
        assertEquals(ShopDeliveryResult.PENDING, result.status());
        assertEquals("等待发放：积分已扣除，请上架者提交发货凭证", result.message());
    }

    @Test
    void pointsRedeemConfigOnlyKeepsDeliveryNote() {
        Map<String, Object> normalized = registry.normalizeConfig(ShopProductTypeRegistry.POINTS_REDEEM_TYPE,
                Map.of("deliveryNote", "  兑换后联系管理员  ", "unexpected", "被忽略"));

        assertEquals(Map.of("deliveryNote", "兑换后联系管理员"), normalized);
        assertEquals(Map.of(), registry.normalizeConfig(ShopProductTypeRegistry.POINTS_REDEEM_TYPE, Map.of()));
        assertEquals(Map.of(), registry.normalizeConfig(ShopProductTypeRegistry.POINTS_REDEEM_TYPE, null));
        assertEquals("发货说明不能超过 500 个字符", assertThrows(IllegalArgumentException.class,
                () -> registry.normalizeConfig(ShopProductTypeRegistry.POINTS_REDEEM_TYPE,
                        Map.of("deliveryNote", "x".repeat(501)))).getMessage());
    }

    @Test
    void unknownTypeIsStillRejectedAtNormalizeTime() {
        assertEquals("商品类型当前不可用：NOPE（提供该类型的插件可能未启用）",
                assertThrows(IllegalArgumentException.class,
                        () -> registry.normalizeConfig("NOPE", Map.of())).getMessage());
        assertEquals("未知", registry.displayName("NOPE"));
        assertEquals("积分兑换", registry.displayName("POINTS_REDEEM"));
    }
}
