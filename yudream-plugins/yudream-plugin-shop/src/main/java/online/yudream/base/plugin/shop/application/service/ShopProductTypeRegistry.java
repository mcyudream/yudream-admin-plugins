package online.yudream.base.plugin.shop.application.service;

import online.yudream.base.plugin.shop.api.PluginShopProductType;
import online.yudream.base.plugin.shop.api.ShopDeliveryContext;
import online.yudream.base.plugin.shop.api.ShopDeliveryResult;
import online.yudream.base.plugin.shop.api.ShopProductTypeHandler;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商品类型注册表：内置 GENERIC 普通商品与 POINTS_REDEEM 积分兑换 + 聚合其他插件经
 * {@code registerExtension(ShopProductTypeHandler.class, ...)} 注册的类型处理器。
 */
public class ShopProductTypeRegistry {

    public static final String GENERIC_TYPE = "GENERIC";
    /** 内置普通商品的可选配置键：给买家的发货说明（卡密、链接、联系方式等）。 */
    public static final String GENERIC_DELIVERY_NOTE = "deliveryNote";

    /** 内置积分兑换类型：支付即扣除买家资产、不转给卖家。 */
    public static final String POINTS_REDEEM_TYPE = "POINTS_REDEEM";
    /** 内置积分兑换商品的可选配置键：上架者给买家的发货说明。 */
    public static final String POINTS_REDEEM_DELIVERY_NOTE = "deliveryNote";

    private final PluginContext context;
    private final ShopProductTypeHandler genericHandler = new GenericProductTypeHandler();
    private final ShopProductTypeHandler pointsRedeemHandler = new PointsRedeemProductTypeHandler();

    public ShopProductTypeRegistry(PluginContext context) {
        this.context = context;
    }

    public List<PluginShopProductType> types() {
        List<PluginShopProductType> types = new ArrayList<>();
        types.add(new PluginShopProductType(GENERIC_TYPE, genericHandler.displayName(),
                genericHandler.description(), true));
        types.add(new PluginShopProductType(POINTS_REDEEM_TYPE, pointsRedeemHandler.displayName(),
                pointsRedeemHandler.description(), true));
        for (ShopProductTypeHandler handler : extensions()) {
            types.add(new PluginShopProductType(handler.type(), handler.displayName(),
                    handler.description(), false));
        }
        return types;
    }

    public Optional<ShopProductTypeHandler> handler(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        if (GENERIC_TYPE.equalsIgnoreCase(type.trim())) {
            return Optional.of(genericHandler);
        }
        if (POINTS_REDEEM_TYPE.equalsIgnoreCase(type.trim())) {
            return Optional.of(pointsRedeemHandler);
        }
        return extensions().stream()
                .filter(handler -> type.trim().equalsIgnoreCase(handler.type()))
                .findFirst();
    }

    /** 上架时经处理器校验并规范化类型配置；类型未注册（提供方插件缺失）时明确报错。 */
    public Map<String, Object> normalizeConfig(String type, Map<String, Object> config) {
        ShopProductTypeHandler handler = handler(type)
                .orElseThrow(() -> new IllegalArgumentException("商品类型当前不可用：" + type + "（提供该类型的插件可能未启用）"));
        Map<String, Object> normalized = handler.normalizeConfig(config == null ? Map.of() : config);
        return normalized == null ? Map.of() : new LinkedHashMap<>(normalized);
    }

    /** 类型展示名；类型未注册（提供方插件缺失）或为空时统一显示「未知」。 */
    public String displayName(String type) {
        return handler(type).map(ShopProductTypeHandler::displayName).orElse("未知");
    }

    /**
     * 类型的结算方式：由处理器声明，未注册类型按 {@link ShopSettlement#SELLER} 处理。
     *
     * <p>发布规则、归属用户与两个入口（玩家市场 / 积分商城）都以此为准：
     * {@code SELLER} 商品归属真实用户，{@code BURN} 商品归属平台（没有归属用户）。
     */
    public ShopSettlement settlementOf(String type) {
        return handler(type).map(ShopProductTypeHandler::settlement).orElse(ShopSettlement.SELLER);
    }

    /** 按结算方式筛选类型：用户端发布入口只提供玩家侧类型，管理端只提供官方侧类型。 */
    public List<PluginShopProductType> types(ShopSettlement settlement) {
        return types().stream()
                .filter(type -> settlementOf(type.type()) == settlement)
                .toList();
    }

    private List<ShopProductTypeHandler> extensions() {
        try {
            return context.extensions(ShopProductTypeHandler.class);
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    /** 内置普通商品：无自动发货，可附带给买家的发货说明，支付成功即完成。 */
    private static class GenericProductTypeHandler implements ShopProductTypeHandler {

        @Override
        public String type() {
            return GENERIC_TYPE;
        }

        @Override
        public String displayName() {
            return "普通商品";
        }

        @Override
        public String description() {
            return "无需自动发货的商品，可在下方填写给买家的发货说明（卡密、链接、联系方式等），买家支付成功后在订单中可见。";
        }

        @Override
        public Map<String, Object> normalizeConfig(Map<String, Object> config) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            Object note = config.get(GENERIC_DELIVERY_NOTE);
            if (note != null && !String.valueOf(note).isBlank()) {
                String text = String.valueOf(note).trim();
                if (text.length() > 500) {
                    throw new IllegalArgumentException("发货说明不能超过 500 个字符");
                }
                normalized.put(GENERIC_DELIVERY_NOTE, text);
            }
            return normalized;
        }

        @Override
        public ShopDeliveryResult deliver(ShopDeliveryContext context) {
            Object note = context.typeConfig() == null ? null : context.typeConfig().get(GENERIC_DELIVERY_NOTE);
            String content = note == null || String.valueOf(note).isBlank() ? null : String.valueOf(note);
            return ShopDeliveryResult.delivered(content == null ? "购买成功" : "购买成功，发货内容如下", content);
        }
    }

    /**
     * 内置积分兑换：支付即扣除买家的商品计价资产、不转给卖家（{@link ShopSettlement#BURN}）。
     *
     * <p>没有自动发货，因此支付成功后订单停在发货中，等上架者提交发货凭证（文本或最多 6 张图片）、
     * 买家核验后才完成；发货前买家可取消，取消退款原路退回买家账户（不依赖任何卖家余额）。
     * 每人限兑复用商品的「每人限购」字段（{@code perUserLimit}，0 为不限），判定在应用层。
     */
    private static class PointsRedeemProductTypeHandler implements ShopProductTypeHandler {

        @Override
        public String type() {
            return POINTS_REDEEM_TYPE;
        }

        @Override
        public String displayName() {
            return "积分兑换";
        }

        @Override
        public String description() {
            return "支付即扣除积分、不转给卖家；上架者需提交发货凭证（可附最多 6 张图），买家确认收货后完成；"
                    + "发货前买家可取消并原路退回积分；每人限兑用商品的『每人限购』字段控制";
        }

        @Override
        public ShopSettlement settlement() {
            return ShopSettlement.BURN;
        }

        @Override
        public Map<String, Object> normalizeConfig(Map<String, Object> config) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            Object note = config.get(POINTS_REDEEM_DELIVERY_NOTE);
            if (note != null && !String.valueOf(note).isBlank()) {
                String text = String.valueOf(note).trim();
                if (text.length() > 500) {
                    throw new IllegalArgumentException("发货说明不能超过 500 个字符");
                }
                normalized.put(POINTS_REDEEM_DELIVERY_NOTE, text);
            }
            return normalized;
        }

        @Override
        public ShopDeliveryResult deliver(ShopDeliveryContext context) {
            return ShopDeliveryResult.pending("等待发放：积分已扣除，请上架者提交发货凭证");
        }
    }
}
