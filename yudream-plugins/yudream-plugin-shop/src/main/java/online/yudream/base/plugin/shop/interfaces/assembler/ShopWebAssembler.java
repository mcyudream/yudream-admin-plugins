package online.yudream.base.plugin.shop.interfaces.assembler;

import online.yudream.base.plugin.shop.api.PluginShopProductType;
import online.yudream.base.plugin.shop.application.cmd.ShopProductSaveCmd;
import online.yudream.base.plugin.shop.application.cmd.ShopVariantCmd;
import online.yudream.base.plugin.shop.application.service.ShopProductTypeRegistry;
import online.yudream.base.plugin.shop.application.service.ShopSettingsService;
import online.yudream.base.plugin.shop.domain.aggregate.ShopOrder;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.aggregate.ShopVariant;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;
import online.yudream.base.plugin.shop.interfaces.request.ShopAdminProductSaveRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopProductSaveRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopVariantRequest;
import online.yudream.base.plugin.shop.interfaces.res.ShopAssetRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopOrderRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopProductRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopProductTypeRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopUserRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopVariantRes;

import java.util.List;

public class ShopWebAssembler {

    private final ShopProductTypeRegistry typeRegistry;
    private final ShopSettingsService settingsService;

    public ShopWebAssembler(ShopProductTypeRegistry typeRegistry, ShopSettingsService settingsService) {
        this.typeRegistry = typeRegistry;
        this.settingsService = settingsService;
    }

    public ShopProductSaveCmd toCmd(ShopProductSaveRequest request) {
        return toCmd(null, request);
    }

    public ShopProductSaveCmd toCmd(String id, ShopProductSaveRequest request) {
        return new ShopProductSaveCmd(id, request.title(), request.summary(), request.descriptionMd(),
                request.images(), request.assetCode(), request.price(),
                request.stock() == null ? 0 : request.stock(), perUserLimit(request.perUserLimit()),
                request.type(), request.typeConfig(), toVariantCmds(request.variants()), null);
    }

    public ShopProductSaveCmd toCmd(ShopAdminProductSaveRequest request) {
        return toCmd(null, request);
    }

    public ShopProductSaveCmd toCmd(String id, ShopAdminProductSaveRequest request) {
        return new ShopProductSaveCmd(id, request.title(), request.summary(), request.descriptionMd(),
                request.images(), request.assetCode(), request.price(),
                request.stock() == null ? 0 : request.stock(), perUserLimit(request.perUserLimit()),
                request.type(), request.typeConfig(), toVariantCmds(request.variants()), request.sortOrder());
    }

    /** 型号请求 → 型号命令；null 项直接丢弃，其余字段校验交给应用层。 */
    private List<ShopVariantCmd> toVariantCmds(List<ShopVariantRequest> variants) {
        if (variants == null || variants.isEmpty()) {
            return List.of();
        }
        return variants.stream()
                .filter(variant -> variant != null)
                .map(variant -> new ShopVariantCmd(variant.id(), variant.name(), variant.price(), variant.stock(),
                        variant.image()))
                .toList();
    }

    /** 每人限购缺省不限（0），负数交给领域层校验拒绝。 */
    private int perUserLimit(Integer value) {
        return value == null ? ShopProduct.UNLIMITED_PER_USER : value;
    }

    /**
     * 商品视图。广场列表不带详情与配置；卖家/管理员视图带 typeConfig。
     * settlement 由类型处理器声明；platformOwned/ownerLabel 供前端展示归属（官方展示名可在商店设置里改）。
     */
    public ShopProductRes toRes(ShopProduct product, ShopUserRes owner, String assetSymbol,
                                boolean includeDetail, boolean includeConfig) {
        return new ShopProductRes(product.id(), product.ownerId(), owner, product.platformOwned(),
                product.platformOwned() ? settingsService.current().platformOwnerLabel() : null,
                product.platformOwned() ? settingsService.current().platformOwnerAvatar() : null,
                product.title(), product.summary(),
                includeDetail ? product.descriptionMd() : null,
                includeDetail ? product.images() : List.of(), product.coverImage(), product.assetCode(),
                assetSymbol, product.price(), product.stock(), product.perUserLimit(),
                product.variants().stream().map(this::toRes).toList(), product.soldCount(),
                product.type(), typeRegistry.displayName(product.type()),
                typeRegistry.settlementOf(product.type()).name(),
                includeConfig ? product.typeConfig() : null,
                product.status().name(), product.status().label(), product.createdAt(), product.updatedAt(),
                product.sortOrder());
    }

    public ShopVariantRes toRes(ShopVariant variant) {
        return new ShopVariantRes(variant.id(), variant.name(), variant.price(), variant.stock(),
                variant.image(), variant.soldOut());
    }

    /**
     * 订单视图。deliveryContent 仅买家本人或管理员视角可见；发货凭证对所有相关方可见。
     * sellerLabel 用于没有卖家账号的消耗式订单（归属展示名，可在商店设置里改成任意名称或留空隐藏）。
     */
    public ShopOrderRes toRes(ShopOrder order, ShopUserRes buyer, ShopUserRes seller, boolean includeContent) {
        return new ShopOrderRes(order.id(), order.productId(), order.productTitle(), order.productImage(),
                order.productType(), typeRegistry.displayName(order.productType()),
                order.settlement().name(), buyer, seller, sellerLabel(order),
                order.assetCode(), order.price(), order.variantId(), order.variantName(),
                order.quantity(), order.totalAmount(),
                order.status().name(), order.status().label(), order.walletTransactionId(),
                order.refundTransactionId(), order.deliveryMessage(),
                includeContent ? order.deliveryContent() : null,
                order.deliveryVoucher(), order.deliveryProofs(), order.cancellableByBuyer(),
                order.verifiedAt() != null,
                order.createdAt(), order.paidAt(), order.deliveredAt());
    }

    /** 消耗式订单没有卖家账号：给出设置里的官方展示名（留空则不显示归属行）。 */
    private String sellerLabel(ShopOrder order) {
        return ShopProduct.PLATFORM_OWNER.equals(order.sellerId())
                ? settingsService.current().platformOwnerLabel()
                : null;
    }

    public ShopAssetRes toRes(ShopWalletPort.WalletAsset asset) {
        return new ShopAssetRes(asset.code(), asset.name(), asset.symbol(), asset.scale());
    }

    public ShopProductTypeRes toRes(PluginShopProductType type) {
        return new ShopProductTypeRes(type.type(), type.displayName(), type.description(), type.builtin());
    }
}
