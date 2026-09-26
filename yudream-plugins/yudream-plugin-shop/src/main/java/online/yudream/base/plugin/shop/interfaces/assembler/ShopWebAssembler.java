package online.yudream.base.plugin.shop.interfaces.assembler;

import online.yudream.base.plugin.shop.api.PluginShopProductType;
import online.yudream.base.plugin.shop.application.cmd.ShopProductSaveCmd;
import online.yudream.base.plugin.shop.application.service.ShopProductTypeRegistry;
import online.yudream.base.plugin.shop.domain.aggregate.ShopOrder;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;
import online.yudream.base.plugin.shop.interfaces.request.ShopAdminProductSaveRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopProductSaveRequest;
import online.yudream.base.plugin.shop.interfaces.res.ShopAssetRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopOrderRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopProductRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopProductTypeRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopUserRes;

import java.util.List;

public class ShopWebAssembler {

    private final ShopProductTypeRegistry typeRegistry;

    public ShopWebAssembler(ShopProductTypeRegistry typeRegistry) {
        this.typeRegistry = typeRegistry;
    }

    public ShopProductSaveCmd toCmd(ShopProductSaveRequest request) {
        return toCmd(null, request);
    }

    public ShopProductSaveCmd toCmd(String id, ShopProductSaveRequest request) {
        return new ShopProductSaveCmd(id, request.title(), request.summary(), request.descriptionMd(),
                request.images(), request.assetCode(), request.price(),
                request.stock() == null ? 0 : request.stock(), request.type(), request.typeConfig());
    }

    public ShopProductSaveCmd toCmd(ShopAdminProductSaveRequest request) {
        return toCmd(null, request);
    }

    public ShopProductSaveCmd toCmd(String id, ShopAdminProductSaveRequest request) {
        return new ShopProductSaveCmd(id, request.title(), request.summary(), request.descriptionMd(),
                request.images(), request.assetCode(), request.price(),
                request.stock() == null ? 0 : request.stock(), request.type(), request.typeConfig());
    }

    /**
     * 商品视图。广场列表不带详情与配置；卖家/管理员视图带 typeConfig。
     */
    public ShopProductRes toRes(ShopProduct product, ShopUserRes owner, String assetSymbol,
                                boolean includeDetail, boolean includeConfig) {
        return new ShopProductRes(product.id(), product.ownerId(), owner, product.title(), product.summary(),
                includeDetail ? product.descriptionMd() : null,
                includeDetail ? product.images() : List.of(), product.coverImage(), product.assetCode(),
                assetSymbol, product.price(), product.stock(), product.soldCount(), product.type(),
                typeRegistry.displayName(product.type()),
                includeConfig ? product.typeConfig() : null,
                product.status().name(), product.status().label(), product.createdAt(), product.updatedAt());
    }

    /**
     * 订单视图。deliveryContent 仅买家本人或管理员视角可见；发货凭证对所有相关方可见。
     */
    public ShopOrderRes toRes(ShopOrder order, ShopUserRes buyer, ShopUserRes seller, boolean includeContent) {
        return new ShopOrderRes(order.id(), order.productId(), order.productTitle(), order.productImage(),
                order.productType(), typeRegistry.displayName(order.productType()), buyer, seller,
                order.assetCode(), order.price(), order.quantity(), order.totalAmount(),
                order.status().name(), order.status().label(), order.walletTransactionId(),
                order.refundTransactionId(), order.deliveryMessage(),
                includeContent ? order.deliveryContent() : null,
                order.deliveryVoucher(), order.verifiedAt() != null,
                order.createdAt(), order.paidAt(), order.deliveredAt());
    }

    public ShopAssetRes toRes(ShopWalletPort.WalletAsset asset) {
        return new ShopAssetRes(asset.code(), asset.name(), asset.symbol(), asset.scale());
    }

    public ShopProductTypeRes toRes(PluginShopProductType type) {
        return new ShopProductTypeRes(type.type(), type.displayName(), type.description(), type.builtin());
    }
}
