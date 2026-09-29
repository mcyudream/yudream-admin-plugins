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
import online.yudream.base.plugin.shop.domain.aggregate.ShopVariant;
import online.yudream.base.plugin.shop.domain.enumerate.ShopOrderStatus;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import online.yudream.base.plugin.shop.domain.enumerate.ShopTradeFeePayee;
import online.yudream.base.plugin.shop.domain.valobj.ShopSettings;
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
 *
 * <p>玩家市场（{@link ShopSettlement#SELLER}）订单可能带交易手续费：买家总支出始终是成交额，
 * 其中卖家实收 net = 成交额 − 手续费，手续费按商店设置销毁或转给平台用户。钱包只支持单收款方，
 * 因此有手续费时拆成「卖家腿 + 手续费腿」两次调用，任何一腿失败都用固定对冲单号把已成功的另一腿转回买家。
 * 官方消耗类（{@link ShopSettlement#BURN}）订单不收手续费，链路与改动前完全一致。
 */
public class ShopOrderService {

    private static final int MAX_QUANTITY = 99;
    /** 手续费腿业务单号前缀（对账口径：shop:fee:&lt;订单号&gt;）。 */
    private static final String FEE_LEG_PREFIX = "shop:fee:";
    /** 购买失败时卖家货款腿的对冲单号前缀。 */
    private static final String NET_REVERT_PREFIX = "shop:net-revert:";
    /** 购买失败时手续费腿的对冲单号前缀。 */
    private static final String FEE_REVERT_PREFIX = "shop:fee-revert:";
    /** 退款时手续费腿的业务单号前缀。 */
    private static final String FEE_REFUND_PREFIX = "shop:fee-refund:";

    private final ShopOrderRepository orderRepository;
    private final ShopProductRepository productRepository;
    private final ShopProductTypeRegistry typeRegistry;
    private final ShopWalletPort walletPort;
    private final ShopSettingsService settingsService;

    public ShopOrderService(ShopOrderRepository orderRepository, ShopProductRepository productRepository,
                            ShopProductTypeRegistry typeRegistry, ShopWalletPort walletPort,
                            ShopSettingsService settingsService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.typeRegistry = typeRegistry;
        this.walletPort = walletPort;
        this.settingsService = settingsService;
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
        // 有型号的商品必须选定型号，价格与库存都以该型号为准
        String variantId = cmd.variantId() == null ? null : cmd.variantId().trim();
        ShopVariant variant = null;
        if (product.hasVariants()) {
            variant = product.variant(variantId)
                    .orElseThrow(() -> new IllegalArgumentException("请选择商品型号"));
        }
        else if (variantId != null && !variantId.isBlank()) {
            throw new IllegalArgumentException("该商品没有型号可选");
        }
        handler.validatePurchase(new ShopPurchaseContext(product.id(), product.type(), product.typeConfig(),
                buyerId, quantity));
        requireWithinPerUserLimit(product, buyerId, quantity);
        ShopSettlement settlement = settlementOf(handler);

        // 支付口径（成交额与手续费快照）在占库存之前确定：读取商店设置出错时不会先扣库存再抛错。
        BigDecimal total = (variant == null ? product.price() : variant.price())
                .multiply(BigDecimal.valueOf(quantity));
        ShopTradeFee fee = tradeFeeOf(product, total, settlement);
        String orderId = UUID.randomUUID().toString().replace("-", "");

        // 先占库存后扣款；扣款失败回滚库存。库存读写与钱包扣款均为读改写，均无跨存储事务，
        // 极端并发下以钱包扣款结果为准人工兜底（管理端可退款）。
        ShopProduct claimed;
        try {
            claimed = product.purchased(quantity, variantId);
        } catch (IllegalArgumentException ex) {
            // 领域层已给出可读原因（商品库存不足 / 型号「X」库存不足），直接透出
            throw new IllegalArgumentException(ex.getMessage() == null ? "商品库存不足" : ex.getMessage());
        }
        productRepository.save(claimed);

        // 无手续费（关闭 / 费率为 0 / 算出的手续费为 0）：保持改动前的单腿链路，不多发 0 金额的钱包操作。
        if (!fee.applied()) {
            ShopWalletPort.WalletPayment payment;
            try {
                payment = settlement == ShopSettlement.BURN
                        ? walletPort.burn(buyerId, claimed.assetCode(), total, "shop:" + orderId,
                                "兑换商品：" + claimed.title())
                        : walletPort.transfer(buyerId, claimed.ownerId(), claimed.assetCode(), total,
                                "shop:" + orderId, "购买商品：" + claimed.title());
            } catch (RuntimeException ex) {
                productRepository.save(claimed.purchaseRolledBack(quantity, variantId));
                throw ex;
            }
            ShopOrder order = orderRepository.save(ShopOrder.paid(orderId, claimed, variant, buyerId, quantity, total,
                    payment.transactionId(), settlement, BigDecimal.ZERO, ShopTradeFeePayee.BURN, null));
            return deliver(order, claimed, handler);
        }

        String walletTransactionId = payWithTradeFee(buyerId, claimed, orderId, fee,
                claimed.purchaseRolledBack(quantity, variantId));
        ShopOrder order = orderRepository.save(ShopOrder.paid(orderId, claimed, variant, buyerId, quantity, total,
                walletTransactionId, settlement, fee.amount(), fee.payee(), fee.payeeUserId()));
        return deliver(order, claimed, handler);
    }

    /**
     * 两腿支付：卖家腿（{@code shop:<订单号>}，保持既有幂等语义与对账口径）走 net，
     * 手续费腿（{@code shop:fee:<订单号>}）按收款方式销毁或转给平台用户。
     *
     * <p>任一腿失败都先把已成功的另一腿按固定对冲单号转回买家、回滚库存，再抛出原异常，
     * 使买家账户回到交易前状态且订单不落成已支付；对冲本身也失败时抛出管理员可见的异常。
     */
    private String payWithTradeFee(String buyerId, ShopProduct product, String orderId, ShopTradeFee fee,
                                   ShopProduct rolledBack) {
        String netLegNo = "shop:" + orderId;
        String feeLegNo = FEE_LEG_PREFIX + orderId;
        String sellerLegTxId = null;
        String feeLegTxId = null;
        try {
            if (fee.sellerAmount().signum() > 0) {
                sellerLegTxId = walletPort.transfer(buyerId, product.ownerId(), product.assetCode(),
                        fee.sellerAmount(), netLegNo, "购买商品：" + product.title()).transactionId();
            }
            if (fee.amount().signum() > 0) {
                feeLegTxId = payFeeLeg(buyerId, product.assetCode(), fee, feeLegNo, product.title()).transactionId();
            }
        } catch (RuntimeException | LinkageError ex) {
            productRepository.save(rolledBack);
            String revertFailure = revertPaidLegs(buyerId, product, orderId, fee, sellerLegTxId != null, feeLegTxId != null);
            if (revertFailure != null) {
                IllegalStateException visible = new IllegalStateException(
                        "购买失败且自动冲正未完成：" + revertFailure + "；原失败原因：" + ex.getMessage()
                                + "。订单未支付，请管理员核对买家、卖家与平台账户流水");
                visible.addSuppressed(ex);
                throw visible;
            }
            if (ex instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("购买失败，已冲正：" + ex.getMessage(), ex);
        }
        return sellerLegTxId != null ? sellerLegTxId : feeLegTxId;
    }

    /** 手续费腿：销毁（从买家账户扣减）或转给平台用户。 */
    private ShopWalletPort.WalletPayment payFeeLeg(String buyerId, String assetCode, ShopTradeFee fee,
                                                   String businessNo, String productTitle) {
        if (fee.payee() != ShopTradeFeePayee.PLATFORM) {
            return walletPort.burn(buyerId, assetCode, fee.amount(), businessNo, "交易手续费：" + productTitle);
        }
        if (fee.payeeUserId() == null || fee.payeeUserId().isBlank()) {
            throw new IllegalStateException("交易手续费收款方式为平台用户，但未配置平台用户 ID，请联系管理员");
        }
        return walletPort.transfer(buyerId, fee.payeeUserId(), assetCode, fee.amount(), businessNo,
                "交易手续费：" + productTitle);
    }

    /**
     * 购买失败时把已成功的那一腿按固定对冲单号转回买家（幂等、可重试）：
     * 卖家腿 {@code shop:net-revert:<订单号>}；手续费腿 {@code shop:fee-revert:<订单号>}
     * （销毁方式把手续费加回买家，平台方式从平台用户转回买家）。
     *
     * @return 全部冲正成功返回 null，否则返回可直接展示的失败说明
     */
    private String revertPaidLegs(String buyerId, ShopProduct product, String orderId, ShopTradeFee fee,
                                  boolean sellerLegPaid, boolean feeLegPaid) {
        String failure = null;
        if (sellerLegPaid && fee.sellerAmount().signum() > 0) {
            try {
                walletPort.transfer(product.ownerId(), buyerId, product.assetCode(), fee.sellerAmount(),
                        NET_REVERT_PREFIX + orderId, "购买失败冲正：" + product.title());
            } catch (RuntimeException | LinkageError ex) {
                failure = "卖家货款腿冲正失败（" + ex.getMessage() + "）";
            }
        }
        if (feeLegPaid && fee.amount().signum() > 0) {
            try {
                if (fee.payee() == ShopTradeFeePayee.PLATFORM) {
                    walletPort.transfer(fee.payeeUserId(), buyerId, product.assetCode(), fee.amount(),
                            FEE_REVERT_PREFIX + orderId, "购买失败冲正手续费：" + product.title());
                } else {
                    walletPort.refund(buyerId, product.assetCode(), fee.amount(), FEE_REVERT_PREFIX + orderId,
                            "购买失败冲正手续费：" + product.title());
                }
            } catch (RuntimeException | LinkageError ex) {
                failure = failure == null ? "手续费腿冲正失败（" + ex.getMessage() + "）" : failure + "；手续费腿冲正失败（"
                        + ex.getMessage() + "）";
            }
        }
        return failure;
    }

    /**
     * 本单的手续费快照：只对玩家市场（SELLER）订单计费，官方消耗类商品一律 0。
     *
     * <p>小数位取该商品计价货币在钱包里的 scale；货币已不存在（钱包查不到）时回退到成交额自身的小数位，
     * 保证手续费不会出现比成交额更细的尾数。
     */
    private ShopTradeFee tradeFeeOf(ShopProduct product, BigDecimal total, ShopSettlement settlement) {
        BigDecimal zero = BigDecimal.ZERO;
        if (settlement != ShopSettlement.SELLER) {
            return new ShopTradeFee(zero, zero, ShopTradeFeePayee.BURN, null);
        }
        ShopSettings settings = settingsService.current();
        if (!settings.chargesTradeFee()) {
            return new ShopTradeFee(zero, total, ShopTradeFeePayee.BURN, null);
        }
        int scale = walletPort.assetScale(product.assetCode()).orElseGet(() -> Math.max(total.scale(), 0));
        BigDecimal amount = settings.tradeFee(total, scale);
        if (amount.signum() <= 0) {
            return new ShopTradeFee(zero, total, ShopTradeFeePayee.BURN, null);
        }
        return new ShopTradeFee(amount, total.subtract(amount), settings.tradeFeePayee(),
                settings.tradeFeePayeeUserId());
    }

    /** 一次购买的钱包拆分：手续费金额、卖家实收、手续费收款方式与收款用户。 */
    private record ShopTradeFee(BigDecimal amount, BigDecimal sellerAmount, ShopTradeFeePayee payee,
                                String payeeUserId) {

        boolean applied() {
            return amount != null && amount.signum() > 0;
        }
    }

    /**
     * 每人限购校验：统计该买家对该商品的历史订单件数（已取消/已退款的订单不计入，额度自动还回），
     * 超出商品的 perUserLimit 时拒绝；未配置限购（0）时不做全量扫描。
     */
    private void requireWithinPerUserLimit(ShopProduct product, String buyerId, int quantity) {
        if (!product.hasPerUserLimit()) {
            return;
        }
        long alreadyBought = orderRepository.findAll().stream()
                .filter(order -> product.id().equals(order.productId()))
                .filter(order -> buyerId.equals(order.buyerId()))
                .filter(order -> order.status() != ShopOrderStatus.CANCELLED
                        && order.status() != ShopOrderStatus.REFUNDED)
                .mapToLong(ShopOrder::quantity)
                .sum();
        if (product.exceedsPerUserLimit(alreadyBought, quantity)) {
            throw new IllegalArgumentException(String.format("每人限兑 %d 件，你已兑换 %d 件",
                    product.perUserLimit(), alreadyBought));
        }
    }

    private ShopSettlement settlementOf(ShopProductTypeHandler handler) {
        ShopSettlement settlement = handler.settlement();
        return settlement == null ? ShopSettlement.SELLER : settlement;
    }

    /**
     * 买家在发货前取消订单（仅买家本人，且订单尚未发货）：先按结算方式原路退款，
     * 再回滚商品库存与销量，最后把订单落为 {@link ShopOrderStatus#CANCELLED} 并记下退款流水。
     *
     * <p>退款失败时抛 {@link IllegalStateException}，订单与库存保持不变，买家可稍后重试
     * （退款业务单号固定为 {@code shop:refund:<订单号>}，重试不会重复退款）。
     */
    public ShopOrder cancelOrder(String buyerId, String orderId, String reason) {
        ShopOrder order = orderRepository.findById(orderId)
                .filter(item -> item.buyerId().equals(buyerId))
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        if (!order.cancellableByBuyer()) {
            throw new IllegalArgumentException("当前状态的订单不能取消：" + order.status().label());
        }
        String refundTxId = refundToBuyer(order);
        if (refundTxId == null) {
            throw new IllegalStateException("取消失败：退款未成功，请稍后重试或联系管理员");
        }
        productRepository.findById(order.productId())
                .ifPresent(product -> productRepository.save(
                        product.purchaseRolledBack(order.quantity(), order.variantId())));
        return orderRepository.save(cancelledWithRefund(order, refundTxId, reason));
    }

    /**
     * 落库「已取消 + 退款流水」。领域层的 {@code cancel(reason)} 只负责状态流转、不接收退款流水号，
     * 这里按记录拷贝补上退款流水，避免为一次写回改动已冻结的领域层签名。
     */
    private ShopOrder cancelledWithRefund(ShopOrder order, String refundTxId, String reason) {
        ShopOrder cancelled = order.cancel(reason);
        return new ShopOrder(cancelled.id(), cancelled.productId(), cancelled.productTitle(),
                cancelled.productImage(), cancelled.productType(), cancelled.settlement(), cancelled.buyerId(),
                cancelled.sellerId(), cancelled.assetCode(), cancelled.price(), cancelled.quantity(),
                cancelled.totalAmount(), cancelled.feeAmount(), cancelled.sellerAmount(), cancelled.feePayee(),
                cancelled.feePayeeUserId(), cancelled.variantId(), cancelled.variantName(), cancelled.status(),
                cancelled.walletTransactionId(), refundTxId,
                cancelled.deliveryMessage(), cancelled.deliveryContent(), cancelled.deliveryVoucher(),
                cancelled.deliveryProofs(), cancelled.verifiedAt(), cancelled.createdAt(), cancelled.paidAt(),
                cancelled.deliveredAt());
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

    /**
     * 原路退款；返回退款流水号，钱包不可用或退款失败返回 null。
     *
     * <p>单腿订单（{@link ShopSettlement#BURN} 消耗式，以及没有手续费的 {@link ShopSettlement#SELLER} 订单，
     * 含全部历史订单）保持既有口径：业务单号 {@code shop:refund:<订单号>}、金额为订单成交额。
     * {@link ShopSettlement#SELLER} 由卖家转回买家（依赖卖家余额）；
     * {@link ShopSettlement#BURN} 直接把买家当初被扣减的资产加回买家，不依赖任何收款方。
     *
     * <p>带手续费的玩家市场订单要收回两腿，买家最终拿回完整成交额：
     * 手续费腿先按订单快照反向收回（销毁方式 {@code credit} 买家、平台方式从平台用户转回买家），
     * 单号 {@code shop:fee-refund:<订单号>}；卖家腿按 sellerAmount 转回买家，单号仍是
     * {@code shop:refund:<订单号>}。两腿单号固定且钱包按单号幂等，因此部分成功后重试不会重复收款：
     * 已成功的那一腿会被钱包直接返回原流水，未完成的那一腿继续推进。
     */
    private String refundToBuyer(ShopOrder order) {
        try {
            String businessNo = "shop:refund:" + order.id();
            String remark = "订单退款：" + order.productTitle();
            if (order.settlement() == ShopSettlement.BURN) {
                return walletPort.refund(order.buyerId(), order.assetCode(), order.totalAmount(), businessNo, remark)
                        .transactionId();
            }
            if (!order.hasTradeFee()) {
                return walletPort.transfer(order.sellerId(), order.buyerId(), order.assetCode(),
                        order.totalAmount(), businessNo, remark).transactionId();
            }
            String feeTxId = null;
            if (order.feeAmount().signum() > 0) {
                String feeBusinessNo = FEE_REFUND_PREFIX + order.id();
                feeTxId = order.feePayee() == ShopTradeFeePayee.PLATFORM
                        ? walletPort.transfer(order.feePayeeUserId(), order.buyerId(), order.assetCode(),
                                order.feeAmount(), feeBusinessNo, remark).transactionId()
                        : walletPort.refund(order.buyerId(), order.assetCode(), order.feeAmount(),
                                feeBusinessNo, remark).transactionId();
            }
            String sellerTxId = null;
            if (order.sellerAmount().signum() > 0) {
                sellerTxId = walletPort.transfer(order.sellerId(), order.buyerId(), order.assetCode(),
                        order.sellerAmount(), businessNo, remark).transactionId();
            }
            return sellerTxId != null ? sellerTxId : feeTxId;
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
     *
     * <p>凭证可以只有文本（卡密、链接、联系方式等）或只有图片，但不能都为空；图片最多 6 张，
     * 且必须是通过平台上传的文件（{@code /api/files/**}），避免前端塞入任意外链。
     */
    public ShopOrder submitDelivery(String sellerId, String orderId, String voucher, List<String> proofs) {
        ShopOrder order = orderRepository.findById(orderId)
                .filter(item -> item.sellerId().equals(sellerId))
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        return applyDelivery(order, voucher, proofs);
    }

    /**
     * 管理员代发货：校验与卖家提交完全一致，但不要求订单归属当前账号。
     *
     * <p>两类订单需要它：一是归属平台的历史订单（旧积分商城迁移过来的存量兑换，sellerId 为
     * {@code ShopProduct.PLATFORM_OWNER}），没有自然人卖家可以提交凭证；二是管理员代他人上架、
     * 需要管理员代为发货的订单。
     */
    public ShopOrder adminSubmitDelivery(String orderId, String voucher, List<String> proofs) {
        ShopOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        return applyDelivery(order, voucher, proofs);
    }

    /** 发货凭证落库：状态与凭证内容校验后转入待买家核验。 */
    private ShopOrder applyDelivery(ShopOrder order, String voucher, List<String> proofs) {
        if (order.status() != ShopOrderStatus.PAID && order.status() != ShopOrderStatus.DELIVERING) {
            throw new IllegalArgumentException("当前状态的订单不能提交发货凭证：" + order.status().label());
        }
        String normalizedVoucher = voucher == null || voucher.isBlank() ? null : voucher.trim();
        List<String> normalizedProofs = normalizeProofs(proofs);
        if (normalizedVoucher == null && normalizedProofs.isEmpty()) {
            throw new IllegalArgumentException("发货凭证不能为空");
        }
        return orderRepository.save(order.markDeliverySubmitted(normalizedVoucher, normalizedProofs));
    }

    /** 过渡方法：仅文本凭证，等价于 {@code submitDelivery(sellerId, orderId, voucher, List.of())}。 */
    public ShopOrder submitVoucher(String sellerId, String orderId, String voucher) {
        return submitDelivery(sellerId, orderId, voucher, List.of());
    }

    /** 买家核验发货凭证：仅有凭证且发货中的本人订单可核验；核验后订单完成且不可退款。 */
    public ShopOrder verifyDelivery(String buyerId, String orderId) {
        ShopOrder order = orderRepository.findById(orderId)
                .filter(item -> item.buyerId().equals(buyerId))
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        if (order.status() != ShopOrderStatus.DELIVERING) {
            throw new IllegalArgumentException("当前状态的订单无需核验：" + order.status().label());
        }
        if (!order.hasDeliveryProof()) {
            throw new IllegalArgumentException("该订单还没有发货凭证，暂时无法核验");
        }
        return orderRepository.save(order.verifyDelivery());
    }

    /** 发货凭证图片规范化：去空、去首尾空格；必须来自平台上传。 */
    private List<String> normalizeProofs(List<String> proofs) {
        if (proofs == null || proofs.isEmpty()) {
            return List.of();
        }
        List<String> normalized = proofs.stream()
                .filter(proof -> proof != null && !proof.isBlank())
                .map(String::trim)
                .toList();
        for (String proof : normalized) {
            if (!proof.startsWith("/api/files/")) {
                throw new IllegalArgumentException("发货凭证图片必须是通过平台上传的文件");
            }
        }
        return normalized;
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

    /** 管理员退款：已发货与已退款订单不可退；按订单的结算方式原路退回买家。 */
    public ShopOrder adminRefund(String orderId) {
        ShopOrder order = adminOrderDetail(orderId);
        if (!order.refundable()) {
            throw new IllegalArgumentException("当前状态的订单不能退款：" + order.status().label());
        }
        String refundTxId = refundToBuyer(order);
        if (refundTxId == null) {
            throw new IllegalStateException(order.settlement() == ShopSettlement.BURN
                    ? "退款失败：钱包插件不可用或退款未成功"
                    : order.hasTradeFee()
                            ? "退款失败：钱包插件不可用、卖家余额不足或手续费收款账户余额不足，请核对后重试"
                            : "退款失败：钱包插件不可用或卖家余额不足");
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
