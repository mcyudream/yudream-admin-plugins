package online.yudream.base.plugin.shop.api;

import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;

import java.util.Map;

/**
 * 商品类型处理器——商店插件的核心扩展点。
 *
 * <p>其他插件实现本接口并通过 {@code PluginContext.registerExtension(ShopProductTypeHandler.class, this)}
 * 注册，即可提供新的商品类型（例如 MC 服务器命令自动发货、卡密库存、兑换码等）。
 * 注册方插件应以 provided 依赖编译本契约，并按 softdepend 降级：商店插件缺失时捕获
 * {@link LinkageError} 静默跳过注册。</p>
 *
 * <p>商店在三个控制点回调处理器：</p>
 * <ul>
 *   <li>上架/编辑：{@link #normalizeConfig(Map)} 校验并规范化类型自定义配置（typeConfig）；</li>
 *   <li>支付前：{@link #validatePurchase(ShopPurchaseContext)} 做限购、资格等业务校验；</li>
 *   <li>支付成功后：{@link #deliver(ShopDeliveryContext)} 执行发货。</li>
 * </ul>
 *
 * <p>除此之外 {@link #settlement()} 决定支付时钱怎么走（转给卖家或消耗），在支付前读取一次并快照进订单。
 */
public interface ShopProductTypeHandler {

    /**
     * 类型 code，全局唯一，建议大写下划线（如 {@code MC_COMMAND}）。
     * 与内置类型 {@code GENERIC} / {@code POINTS_REDEEM} 冲突的注册会被忽略。
     */
    String type();

    /** 展示给上架者与买家的类型名称。 */
    String displayName();

    /** 类型说明，展示给上架者，用于说明 typeConfig 的填写方式与发货行为。 */
    default String description() {
        return "";
    }

    /**
     * 该类型的钱怎么走。默认 {@link ShopSettlement#SELLER}：买家付款经钱包转给卖家（C2C 交易）。
     *
     * <p>返回 {@link ShopSettlement#BURN} 时改为消耗式结算：只从买家账户扣减商品计价资产、不产生收款方
     * （积分兑换类商品）。两种方式的订单生命周期一致（支付→发货→核验），退款都原路退回买家账户，
     * 且业务单号仍为 {@code shop:<订单号>}（退款为 {@code shop:refund:<订单号>}），保证钱包侧幂等与对账。
     */
    default ShopSettlement settlement() {
        return ShopSettlement.SELLER;
    }

    /**
     * 上架/编辑时校验并规范化类型自定义配置；配置非法时抛 {@link IllegalArgumentException}。
     * 返回值会被原样持久化并在购买、发货时回传，不应包含密码、token 等机密（机密请放宿主 secrets）。
     */
    Map<String, Object> normalizeConfig(Map<String, Object> config);

    /**
     * 支付前校验（限购、资格、在线状态等）；不允许购买时抛 {@link IllegalArgumentException}，消息直接展示给买家。
     */
    default void validatePurchase(ShopPurchaseContext context) {
    }

    /**
     * 支付成功后发货。返回 {@link ShopDeliveryResult}：
     * DELIVERED 立即完成；PENDING 进入发货中，稍后由处理器调用
     * {@link PluginShopService#completeDelivery(String, ShopDeliveryUpdate)} 推进终态；
     * FAILED 发货失败并触发自动退款。实现抛出异常等同 FAILED。
     */
    ShopDeliveryResult deliver(ShopDeliveryContext context);
}
