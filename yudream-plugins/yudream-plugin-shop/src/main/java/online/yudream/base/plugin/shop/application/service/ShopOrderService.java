package online.yudream.base.plugin.shop.application.service;

import online.yudream.base.plugin.shop.api.PluginShopOrder;
import online.yudream.base.plugin.shop.api.ShopDeliveryContext;
import online.yudream.base.plugin.shop.api.ShopDeliveryResult;
import online.yudream.base.plugin.shop.api.ShopDeliveryUpdate;
import online.yudream.base.plugin.shop.api.ShopProductTypeHandler;
import online.yudream.base.plugin.shop.api.ShopPurchaseContext;
import online.yudream.base.plugin.shop.application.cmd.ShopPurchaseCmd;
import online.yudream.base.plugin.shop.domain.aggregate.ShopOrder;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.enumerate.ShopOrderStatus;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopOrderRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 订单用例：购买支付、发货编排（含商品类型扩展点回调与自动退款）、买卖双方与管理员订单查询。
 */
public class ShopOrderService {

    private static final int MAX_QUANTITY = 99;

    private final ShopOrderRepository orderRepository;
    private final ShopProductRepository productRepository;
    private final ShopProductTypeRegistry typeRegistry;
    private final ShopWalletPort walletPort;

    public ShopOrderService(ShopOrderRepository orderRepository, ShopProductRepository productRepository,
                            ShopProductTypeRegistry typeRegistry, ShopWalletPort walletPort) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.typeRegistry = typeRegistry;
        this.walletPort = walletPort;
    }

    // ---------- 购买（use 权限） ----------

    public ShopOrder purchase(String buyerId, ShopPurchaseCmd cmd) {
        if (!walletPort.available()) {
            throw new IllegalStateException("钱包插件未启用，暂时无法购买商品");
        }
        if (cmd == null || cmd.productId() == null || cmd.productId().isBlank()) {
            throw new IllegalArgumentException("商品不能为空");
        }
        int quantity = cmd.quantity() <= 0 ? 1 : Math.min(cmd.quantity(), MAX_QUANTITY);
        ShopProduct product = productRepository.findById(cmd.productId().trim())
                .filter(ShopProduct::onShelf)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在或已下架"));
        if (product.ownerId().equals(buyerId)) {
            throw new IllegalArgumentException("不能购买自己上架的商品");
        }
        ShopProductTypeHandler handler = typeRegistry.handler(product.type())
                .orElseThrow(() -> new IllegalStateException("该商品类型暂不可用（提供方插件未启用），无法购买"));
        handler.validatePurchase(new ShopPurchaseContext(product.id(), product.type(), product.typeConfig(),
                buyerId, quantity));

        // 先占库存后扣款；扣款失败回滚库存。库存读写与钱包扣款均为读改写，均无跨存储事务，
        // 极端并发下以钱包扣款结果为准人工兜底（管理端可退款）。
        ShopProduct claimed;
        try {
            claimed = product.purchased(quantity);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("商品库存不足");
        }
        productRepository.save(claimed);

        BigDecimal total = claimed.price().multiply(BigDecimal.valueOf(quantity));
        String orderId = UUID.randomUUID().toString().replace("-", "");
        ShopWalletPort.WalletPayment payment;
        try {
            payment = walletPort.transfer(buyerId, claimed.ownerId(), claimed.assetCode(), total,
                    "shop:" + orderId, "购买商品：" + claimed.title());
        } catch (RuntimeException ex) {
            productRepository.save(claimed.purchaseRolledBack(quantity));
            throw ex;
        }

        ShopOrder order = orderRepository.save(ShopOrder.paid(orderId, claimed, buyerId, quantity, total,
                payment.transactionId()));
        return deliver(order, claimed, handler);
    }

    /** 支付成功后执行发货；处理器异常等同发货失败并自动退款。 */
    private ShopOrder deliver(ShopOrder order, ShopProduct product, ShopProductTypeHandler handler) {
        ShopDeliveryResult result;
        try {
            result = handler.deliver(new ShopDeliveryContext(order.id(), product.id(), product.type(),
                    product.typeConfig(), order.buyerId(), order.quantity()));
        } catch (RuntimeException | LinkageError ex) {
            result = ShopDeliveryResult.failed("发货执行失败：" + ex.getMessage());
        }
        if (result == null || result.status() == null) {
            result = ShopDeliveryResult.pending("发货处理中");
        }
        return switch (result.status()) {
            case ShopDeliveryResult.DELIVERED -> orderRepository.save(order.markDelivered(result.message(), result.content()));
            case ShopDeliveryResult.FAILED -> failAndRefund(order, result.message());
            default -> orderRepository.save(order.markDelivering(result.message()));
        };
    }

    /** 发货失败：尝试自动原路退款，退款结果决定订单终态。 */
    private ShopOrder failAndRefund(ShopOrder order, String message) {
        String refundTxId = refundToBuyer(order);
        if (refundTxId != null) {
            return orderRepository.save(order.markRefunded(refundTxId,
                    appendMessage(message, "发货失败，货款已原路退回")));
        }
        return orderRepository.save(order.markDeliveryFailed(
                appendMessage(message, "自动退款未成功，请联系管理员处理")));
    }

    /** 卖家 → 买家原路退款；返回退款流水号，钱包不可用或退款失败返回 null。 */
    private String refundToBuyer(ShopOrder order) {
        try {
            return walletPort.transfer(order.sellerId(), order.buyerId(), order.assetCode(),
                    order.totalAmount(), "shop:refund:" + order.id(),
                    "订单退款：" + order.productTitle()).transactionId();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    // ---------- 用户订单查询（归属：买家本人或商品卖家本人） ----------

    public ShopPage<ShopOrder> pageMyPurchases(String buyerId, int page, int size) {
        List<ShopOrder> filtered = orderRepository.findAll().stream()
                .filter(order -> order.buyerId().equals(buyerId))
                .sorted(Comparator.comparingLong(ShopOrder::createdAt).reversed())
                .toList();
        return ShopPage.of(filtered, page, size);
    }

    public ShopPage<ShopOrder> pageMySales(String sellerId, int page, int size) {
        List<ShopOrder> filtered = orderRepository.findAll().stream()
                .filter(order -> order.sellerId().equals(sellerId))
                .sorted(Comparator.comparingLong(ShopOrder::createdAt).reversed())
                .toList();
        return ShopPage.of(filtered, page, size);
    }

    /** 订单仅买家本人与卖家本人可见。 */
    public ShopOrder myOrderDetail(String userId, String orderId) {
        return orderRepository.findById(orderId)
                .filter(order -> order.buyerId().equals(userId) || order.sellerId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
    }

    // ---------- 发货凭证核验 ----------

    /**
     * 卖家提交发货凭证：仅本人订单且处于已支付/发货中状态可提交或更新；
     * 提交后订单进入发货中（待买家核验）。已有异步发货流程的订单仍可被处理器回调推进。
     */
    public ShopOrder submitVoucher(String sellerId, String orderId, String voucher) {
        ShopOrder order = orderRepository.findById(orderId)
                .filter(item -> item.sellerId().equals(sellerId))
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        if (order.status() != ShopOrderStatus.PAID && order.status() != ShopOrderStatus.DELIVERING) {
            throw new IllegalArgumentException("当前状态的订单不能提交发货凭证：" + order.status().label());
        }
        if (voucher == null || voucher.isBlank()) {
            throw new IllegalArgumentException("发货凭证不能为空");
        }
        String normalized = voucher.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("发货凭证不能超过 500 个字符");
        }
        return orderRepository.save(order.markVoucherSubmitted(normalized));
    }

    /** 买家核验发货凭证：仅有凭证且发货中的本人订单可核验；核验后订单完成且不可退款。 */
    public ShopOrder verifyDelivery(String buyerId, String orderId) {
        ShopOrder order = orderRepository.findById(orderId)
                .filter(item -> item.buyerId().equals(buyerId))
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        if (order.status() != ShopOrderStatus.DELIVERING) {
            throw new IllegalArgumentException("当前状态的订单无需核验：" + order.status().label());
        }
        if (order.deliveryVoucher() == null || order.deliveryVoucher().isBlank()) {
            throw new IllegalArgumentException("该订单还没有发货凭证，暂时无法核验");
        }
        return orderRepository.save(order.verifyDelivery());
    }

    // ---------- 管理员订单治理 ----------

    public ShopPage<ShopOrder> adminPageOrders(String keyword, String buyerId, String sellerId, String status,
                                               int page, int size) {
        List<ShopOrder> filtered = orderRepository.findAll().stream()
                .filter(order -> !hasText(buyerId) || order.buyerId().equals(buyerId.trim()))
                .filter(order -> !hasText(sellerId) || order.sellerId().equals(sellerId.trim()))
                .filter(order -> !hasText(status) || order.status().name().equalsIgnoreCase(status.trim()))
                .filter(order -> !hasText(keyword)
                        || order.productTitle().toLowerCase().contains(keyword.trim().toLowerCase())
                        || order.id().startsWith(keyword.trim()))
                .sorted(Comparator.comparingLong(ShopOrder::createdAt).reversed())
                .toList();
        return ShopPage.of(filtered, page, size);
    }

    public ShopOrder adminOrderDetail(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
    }

    /** 重新发货：仅发货中/发货失败的订单可重试，按当前商品类型处理器重跑发货流程。 */
    public ShopOrder adminRedeliver(String orderId) {
        ShopOrder order = adminOrderDetail(orderId);
        if (order.status() != ShopOrderStatus.DELIVERING && order.status() != ShopOrderStatus.DELIVERY_FAILED
                && order.status() != ShopOrderStatus.PAID) {
            throw new IllegalArgumentException("当前状态的订单不能重新发货：" + order.status().label());
        }
        ShopProduct product = productRepository.findById(order.productId())
                .orElseThrow(() -> new IllegalArgumentException("商品已删除，无法重新发货"));
        ShopProductTypeHandler handler = typeRegistry.handler(order.productType())
                .orElseThrow(() -> new IllegalStateException("该商品类型暂不可用（提供方插件未启用），无法重新发货"));
        ShopOrder delivering = orderRepository.save(order.markDelivering("管理员触发重新发货"));
        return deliver(delivering, product, handler);
    }

    /** 管理员退款：已发货与已退款订单不可退。 */
    public ShopOrder adminRefund(String orderId) {
        ShopOrder order = adminOrderDetail(orderId);
        if (!order.refundable()) {
            throw new IllegalArgumentException("当前状态的订单不能退款：" + order.status().label());
        }
        String refundTxId = refundToBuyer(order);
        if (refundTxId == null) {
            throw new IllegalStateException("退款失败：钱包插件不可用或卖家余额不足");
        }
        return orderRepository.save(order.markRefunded(refundTxId, "管理员退款"));
    }

    // ---------- 跨插件服务实现 ----------

    public Optional<PluginShopOrder> findOrder(String orderId) {
        return orderRepository.findById(orderId).map(this::toSpi);
    }

    /** 异步发货回调：仅发货中（含已支付待发货）的订单可推进。 */
    public boolean completeDelivery(String orderId, ShopDeliveryUpdate update) {
        if (update == null || update.status() == null) {
            return false;
        }
        Optional<ShopOrder> found = orderRepository.findById(orderId);
        if (found.isEmpty() || !found.get().delivering()) {
            return false;
        }
        ShopOrder order = found.get();
        switch (update.status()) {
            case ShopDeliveryResult.DELIVERED ->
                    orderRepository.save(order.markDelivered(update.message(), update.content()));
            case ShopDeliveryResult.FAILED -> failAndRefund(order, update.message());
            default -> orderRepository.save(order.markDelivering(update.message()));
        }
        return true;
    }

    // ---------- 钱包状态（前端购买卡片展示） ----------

    public boolean walletAvailable() {
        return walletPort.available();
    }

    public Optional<BigDecimal> balanceOf(String userId, String assetCode) {
        return walletPort.balance(userId, assetCode);
    }

    private PluginShopOrder toSpi(ShopOrder order) {
        return new PluginShopOrder(order.id(), order.productId(), order.productTitle(), order.productType(),
                order.buyerId(), order.sellerId(), order.assetCode(), order.price(), order.quantity(),
                order.totalAmount(), order.status().name(), order.walletTransactionId(),
                order.deliveryMessage(), order.deliveryContent(), order.createdAt(), order.deliveredAt());
    }

    private String appendMessage(String message, String suffix) {
        String base = message == null || message.isBlank() ? "" : message.trim() + "；";
        return base + suffix;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
