package online.yudream.base.plugin.shop.infrastructure.repository;

import online.yudream.base.plugin.shop.domain.aggregate.ShopOrder;
import online.yudream.base.plugin.shop.domain.enumerate.ShopOrderStatus;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import online.yudream.base.plugin.shop.domain.enumerate.ShopTradeFeePayee;
import online.yudream.base.plugin.shop.infrastructure.support.DocumentSupport;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository.boxedLongValue;
import static online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository.decimalValue;
import static online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository.intValue;
import static online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository.longValue;
import static online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository.stringValue;

public class ShopOrderRepository {

    private static final int SCAN_PAGE_SIZE = 200;
    private static final String ORDERS = "orders";

    private final PluginDocumentStore documents;

    public ShopOrderRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public ShopOrder save(ShopOrder order) {
        return toOrder(documents.save(ORDERS, order.id(), DocumentSupport.stripNulls(orderDocument(order))));
    }

    public Optional<ShopOrder> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(ORDERS, id.trim()).map(this::toOrder);
    }

    /** 全量扫描（过滤与排序在应用层内存完成）。 */
    public List<ShopOrder> findAll() {
        List<ShopOrder> records = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(ORDERS, page, SCAN_PAGE_SIZE);
            batch.forEach(document -> records.add(toOrder(document)));
            if (batch.size() < SCAN_PAGE_SIZE) {
                return records;
            }
            page++;
        }
    }

    /** 商品是否仍有未进入终态的订单（删除商品前的保护性检查）。 */
    public boolean existsDeliveringByProduct(String productId) {
        if (productId == null || productId.isBlank()) {
            return false;
        }
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findByField(ORDERS, "productId", productId, page, SCAN_PAGE_SIZE);
            for (Map<String, Object> document : batch) {
                ShopOrderStatus status = parseStatus(document.get("status"));
                if (status == ShopOrderStatus.PAID || status == ShopOrderStatus.DELIVERING) {
                    return true;
                }
            }
            if (batch.size() < SCAN_PAGE_SIZE) {
                return false;
            }
            page++;
        }
    }

    private Map<String, Object> orderDocument(ShopOrder order) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", order.id());
        document.put("productId", order.productId());
        document.put("productTitle", order.productTitle());
        document.put("productImage", order.productImage());
        document.put("productType", order.productType());
        document.put("settlement", order.settlement() == null ? null : order.settlement().name());
        document.put("buyerId", order.buyerId());
        document.put("sellerId", order.sellerId());
        document.put("assetCode", order.assetCode());
        document.put("price", order.price() == null ? null : order.price().toPlainString());
        document.put("quantity", order.quantity());
        document.put("totalAmount", order.totalAmount() == null ? null : order.totalAmount().toPlainString());
        document.put("feeAmount", order.feeAmount() == null ? null : order.feeAmount().toPlainString());
        document.put("sellerAmount", order.sellerAmount() == null ? null : order.sellerAmount().toPlainString());
        document.put("feePayee", order.feePayee() == null ? null : order.feePayee().name());
        document.put("feePayeeUserId", order.feePayeeUserId());
        document.put("variantId", order.variantId());
        document.put("variantName", order.variantName());
        document.put("status", order.status().name());
        document.put("walletTransactionId", order.walletTransactionId());
        document.put("refundTransactionId", order.refundTransactionId());
        document.put("deliveryMessage", order.deliveryMessage());
        document.put("deliveryContent", order.deliveryContent());
        document.put("deliveryVoucher", order.deliveryVoucher());
        document.put("deliveryProofs",
                order.deliveryProofs() == null ? List.of() : new ArrayList<>(order.deliveryProofs()));
        document.put("verifiedAt", order.verifiedAt());
        document.put("createdAt", order.createdAt());
        document.put("paidAt", order.paidAt());
        document.put("deliveredAt", order.deliveredAt());
        return document;
    }

    private ShopOrder toOrder(Map<String, Object> document) {
        // 旧文档没有 feeAmount / sellerAmount / feePayee 字段：聚合的紧凑构造按「手续费 0、卖家实收 = 成交额」补齐。
        return new ShopOrder(
                stringValue(document.get("id")),
                stringValue(document.get("productId")),
                stringValue(document.get("productTitle")),
                stringValue(document.get("productImage")),
                stringValue(document.get("productType")),
                ShopSettlement.from(stringValue(document.get("settlement"))),
                stringValue(document.get("buyerId")),
                stringValue(document.get("sellerId")),
                stringValue(document.get("assetCode")),
                decimalValue(document.get("price")),
                intValue(document.get("quantity"), 1),
                decimalValue(document.get("totalAmount")),
                decimalValue(document.get("feeAmount")),
                decimalValue(document.get("sellerAmount")),
                ShopTradeFeePayee.from(stringValue(document.get("feePayee"))),
                stringValue(document.get("feePayeeUserId")),
                stringValue(document.get("variantId")),
                stringValue(document.get("variantName")),
                parseStatus(document.get("status")),
                stringValue(document.get("walletTransactionId")),
                stringValue(document.get("refundTransactionId")),
                stringValue(document.get("deliveryMessage")),
                stringValue(document.get("deliveryContent")),
                stringValue(document.get("deliveryVoucher")),
                document.get("deliveryProofs") instanceof List<?> proofs
                        ? proofs.stream().map(String::valueOf).toList() : List.of(),
                boxedLongValue(document.get("verifiedAt")),
                longValue(document.get("createdAt"), 0L),
                longValue(document.get("paidAt"), 0L),
                boxedLongValue(document.get("deliveredAt"))
        );
    }

    private ShopOrderStatus parseStatus(Object value) {
        try {
            return value == null ? ShopOrderStatus.PAID : ShopOrderStatus.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return ShopOrderStatus.PAID;
        }
    }
}
