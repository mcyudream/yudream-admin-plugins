package online.yudream.base.plugin.shop.application.service;

import online.yudream.base.plugin.shop.api.PluginShopProductType;
import online.yudream.base.plugin.shop.api.ShopDeliveryContext;
import online.yudream.base.plugin.shop.api.ShopDeliveryResult;
import online.yudream.base.plugin.shop.api.ShopProductTypeHandler;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商品类型注册表：内置 GENERIC 普通商品 + 聚合其他插件经
 * {@code registerExtension(ShopProductTypeHandler.class, ...)} 注册的类型处理器。
 */
public class ShopProductTypeRegistry {

    public static final String GENERIC_TYPE = "GENERIC";
    /** 内置普通商品的可选配置键：给买家的发货说明（卡密、链接、联系方式等）。 */
    public static final String GENERIC_DELIVERY_NOTE = "deliveryNote";

    private final PluginContext context;
    private final ShopProductTypeHandler genericHandler = new GenericProductTypeHandler();

    public ShopProductTypeRegistry(PluginContext context) {
        this.context = context;
    }

    public List<PluginShopProductType> types() {
        List<PluginShopProductType> types = new ArrayList<>();
        types.add(new PluginShopProductType(GENERIC_TYPE, genericHandler.displayName(),
                genericHandler.description(), true));
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

    public String displayName(String type) {
        return handler(type).map(ShopProductTypeHandler::displayName).orElse(type == null ? "未知" : type);
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
}
