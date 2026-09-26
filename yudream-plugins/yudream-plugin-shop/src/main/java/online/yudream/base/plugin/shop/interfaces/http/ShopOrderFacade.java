package online.yudream.base.plugin.shop.interfaces.http;

import online.yudream.base.plugin.shop.application.cmd.ShopPurchaseCmd;
import online.yudream.base.plugin.shop.application.service.ShopOrderService;
import online.yudream.base.plugin.shop.application.service.ShopPage;
import online.yudream.base.plugin.shop.domain.aggregate.ShopOrder;
import online.yudream.base.plugin.shop.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.shop.interfaces.assembler.ShopWebAssembler;
import online.yudream.base.plugin.shop.interfaces.request.ShopPurchaseRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopVoucherRequest;
import online.yudream.base.plugin.shop.interfaces.res.ShopOrderRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopUserRes;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.List;
import java.util.Map;

import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.firstQuery;
import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.page;
import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.pathSegment;
import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.requireUserId;
import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.size;

/** 订单 HTTP 门面：购买、我的购买/出售、管理员订单治理。 */
public class ShopOrderFacade {

    private final ShopOrderService orderService;
    private final ShopWebAssembler assembler;
    private final PluginContext context;

    public ShopOrderFacade(ShopOrderService orderService, ShopWebAssembler assembler, PluginContext context) {
        this.orderService = orderService;
        this.assembler = assembler;
        this.context = context;
    }

    // ---------- 买家 ----------

    public PluginHttpResponse purchase(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopPurchaseRequest body = JsonSupport.read(request.body(), ShopPurchaseRequest.class);
        ShopOrder order = orderService.purchase(userId,
                new ShopPurchaseCmd(body.productId(), body.quantity() == null ? 1 : body.quantity()));
        return PluginHttpResponse.ok(assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()), true));
    }

    public PluginHttpResponse myPurchases(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopPage<ShopOrder> result = orderService.pageMyPurchases(userId, page(request), size(request));
        List<ShopOrderRes> records = result.records().stream()
                .map(order -> assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()), true))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", result.total()));
    }

    public PluginHttpResponse mySales(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopPage<ShopOrder> result = orderService.pageMySales(userId, page(request), size(request));
        // 发货内容仅买家可见，卖家视图不含
        List<ShopOrderRes> records = result.records().stream()
                .map(order -> assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()), false))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", result.total()));
    }

    public PluginHttpResponse myOrderDetail(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopOrder order = orderService.myOrderDetail(userId, pathSegment(request.path(), 2));
        boolean viewerIsBuyer = order.buyerId().equals(userId);
        return PluginHttpResponse.ok(assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()),
                viewerIsBuyer));
    }

    /** 购买卡片展示：钱包可用性与指定货币余额。 */
    public PluginHttpResponse myBalance(PluginHttpRequest request) {
        String userId = requireUserId(request);
        String assetCode = firstQuery(request, "assetCode");
        boolean available = orderService.walletAvailable();
        return PluginHttpResponse.ok(Map.of(
                "available", available,
                "balance", available && assetCode != null
                        ? orderService.balanceOf(userId, assetCode).map(Object::toString).orElse("0")
                        : "0"));
    }

    // ---------- 发货凭证核验 ----------

    /** 卖家提交发货凭证（publish 权限）：订单进入待买家核验状态。 */
    public PluginHttpResponse submitVoucher(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopVoucherRequest body = JsonSupport.read(request.body(), ShopVoucherRequest.class);
        ShopOrder order = orderService.submitVoucher(userId, pathSegment(request.path(), 2), body.voucher());
        return PluginHttpResponse.ok(assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()), false));
    }

    /** 买家核验发货凭证（use 权限）：核验后订单完成。 */
    public PluginHttpResponse verifyDelivery(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopOrder order = orderService.verifyDelivery(userId, pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()), true));
    }

    // ---------- 管理员 ----------

    public PluginHttpResponse adminOrders(PluginHttpRequest request) {
        ShopPage<ShopOrder> result = orderService.adminPageOrders(
                firstQuery(request, "keyword"), firstQuery(request, "buyerId"),
                firstQuery(request, "sellerId"), firstQuery(request, "status"),
                page(request), size(request));
        List<ShopOrderRes> records = result.records().stream()
                .map(order -> assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()), false))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", result.total()));
    }

    public PluginHttpResponse adminOrderDetail(PluginHttpRequest request) {
        ShopOrder order = orderService.adminOrderDetail(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()), true));
    }

    public PluginHttpResponse adminRedeliver(PluginHttpRequest request) {
        ShopOrder order = orderService.adminRedeliver(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()), true));
    }

    public PluginHttpResponse adminRefund(PluginHttpRequest request) {
        ShopOrder order = orderService.adminRefund(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(assembler.toRes(order, userOf(order.buyerId()), userOf(order.sellerId()), true));
    }

    // ---------- 内部 ----------

    private ShopUserRes userOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return context.framework().users().findById(Long.parseLong(userId))
                    .map(user -> new ShopUserRes(String.valueOf(user.id()), user.username(), user.nickname(), user.avatar()))
                    .orElse(null);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
