package online.yudream.base.plugin.shop.bootstrap;

import online.yudream.base.plugin.shop.api.PluginShopService;
import online.yudream.base.plugin.shop.application.service.PluginShopServiceImpl;
import online.yudream.base.plugin.shop.application.service.ShopCatalogService;
import online.yudream.base.plugin.shop.application.service.ShopOrderService;
import online.yudream.base.plugin.shop.application.service.ShopProductTypeRegistry;
import online.yudream.base.plugin.shop.application.service.ShopSettingsService;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopOrderRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopSettingsRepository;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;
import online.yudream.base.plugin.shop.interfaces.assembler.ShopWebAssembler;
import online.yudream.base.plugin.shop.interfaces.controller.ShopAdminController;
import online.yudream.base.plugin.shop.interfaces.controller.ShopPlazaController;
import online.yudream.base.plugin.shop.interfaces.controller.ShopUserController;
import online.yudream.base.plugin.shop.interfaces.http.ShopCatalogFacade;
import online.yudream.base.plugin.shop.interfaces.http.ShopOrderFacade;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(
        code = ShopPlugin.CODE,
        name = "shop",
        version = "1.3.0",
        description = "玩家市场与积分商城分离呈现，多图与 Markdown 详情、钱包支付购买，开放商品类型与自动发货扩展点。"
)
@PluginPermissions({
        @PluginPermission(code = ShopPlugin.VIEW_PERMISSION, name = "浏览商店", module = "平台插件", description = "浏览玩家市场、积分商城与商品详情"),
        @PluginPermission(code = ShopPlugin.USE_PERMISSION, name = "购买商品", module = "平台插件", description = "使用钱包购买商品并查看自己的订单"),
        @PluginPermission(code = ShopPlugin.PUBLISH_PERMISSION, name = "上架商品", module = "平台插件", description = "发布与管理自己的商品，查看自己的出售订单"),
        @PluginPermission(code = ShopPlugin.MANAGE_PERMISSION, name = "管理商店", module = "平台插件", description = "跨用户管理商品与订单，强制上下架、删除、重新发货与退款")
})
@PluginFrontend(
        moduleName = "shop",
        menuTitle = "商店",
        menuIcon = "i-ri:store-2-line",
        menuSort = 35,
        styles = {"style.css"},
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/shop",
                        name = "platform-plugin-shop",
                        title = "玩家市场",
                        icon = "i-ri:store-2-line",
                        component = "shop/Plaza",
                        permission = ShopPlugin.VIEW_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/exchange",
                        name = "platform-plugin-shop-exchange",
                        title = "积分商城",
                        icon = "i-ri:gift-2-line",
                        component = "shop/Exchange",
                        permission = ShopPlugin.VIEW_PERMISSION,
                        sort = 15
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/detail",
                        name = "platform-plugin-shop-detail",
                        title = "商品详情",
                        icon = "i-ri:file-text-line",
                        component = "shop/ProductDetail",
                        permission = ShopPlugin.VIEW_PERMISSION,
                        sort = 20,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/orders",
                        name = "platform-plugin-shop-orders",
                        title = "我的购买",
                        icon = "i-ri:shopping-bag-3-line",
                        component = "shop/MyOrders",
                        permission = ShopPlugin.USE_PERMISSION,
                        sort = 30
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/selling",
                        name = "platform-plugin-shop-selling",
                        title = "我的商品",
                        icon = "i-ri:archive-stack-line",
                        component = "shop/MyProducts",
                        permission = ShopPlugin.PUBLISH_PERMISSION,
                        sort = 40
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/selling/edit",
                        name = "platform-plugin-shop-selling-edit",
                        title = "发布商品",
                        icon = "i-ri:add-box-line",
                        component = "shop/ProductEdit",
                        permission = ShopPlugin.PUBLISH_PERMISSION,
                        sort = 45,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/sales",
                        name = "platform-plugin-shop-sales",
                        title = "我的出售",
                        icon = "i-ri:hand-coin-line",
                        component = "shop/MySales",
                        permission = ShopPlugin.PUBLISH_PERMISSION,
                        sort = 50
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/system/products",
                        name = "platform-plugin-shop-admin-products",
                        title = "商品管理",
                        icon = "i-ri:archive-line",
                        parentPath = "/platform/plugins/shop/system",
                        parentTitle = "商店管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 90,
                        component = "shop/AdminProducts",
                        permission = ShopPlugin.MANAGE_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/system/products/edit",
                        name = "platform-plugin-shop-admin-product-edit",
                        title = "商品编辑",
                        icon = "i-ri:add-box-line",
                        parentPath = "/platform/plugins/shop/system",
                        parentTitle = "商店管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 90,
                        component = "shop/AdminProductEdit",
                        permission = ShopPlugin.MANAGE_PERMISSION,
                        sort = 15,
                        hideInMenu = true
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/system/orders",
                        name = "platform-plugin-shop-admin-orders",
                        title = "订单管理",
                        icon = "i-ri:file-list-3-line",
                        parentPath = "/platform/plugins/shop/system",
                        parentTitle = "商店管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 90,
                        component = "shop/AdminOrders",
                        permission = ShopPlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/shop/system/settings",
                        name = "platform-plugin-shop-admin-settings",
                        title = "商店设置",
                        icon = "i-ri:equalizer-line",
                        parentPath = "/platform/plugins/shop/system",
                        parentTitle = "商店管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 90,
                        component = "shop/AdminSettings",
                        permission = ShopPlugin.MANAGE_PERMISSION,
                        sort = 30
                )
        }
)
public class ShopPlugin implements YuDreamPlugin {

    public static final String CODE = "shop";
    public static final String VIEW_PERMISSION = "plugin:shop:view";
    public static final String USE_PERMISSION = "plugin:shop:use";
    public static final String PUBLISH_PERMISSION = "plugin:shop:publish";
    public static final String MANAGE_PERMISSION = "plugin:shop:manage";

    @Override
    public void onEnable(PluginContext context) {
        ShopProductRepository productRepository = new ShopProductRepository(context.documents());
        ShopOrderRepository orderRepository = new ShopOrderRepository(context.documents());
        ShopWalletPort walletPort = ShopWalletPort.create(context);
        ShopProductTypeRegistry typeRegistry = new ShopProductTypeRegistry(context);
        ShopSettingsService settingsService = new ShopSettingsService(
                new ShopSettingsRepository(context.documents()), walletPort);
        ShopCatalogService catalogService = new ShopCatalogService(productRepository, orderRepository,
                typeRegistry, walletPort, settingsService);
        ShopOrderService orderService = new ShopOrderService(orderRepository, productRepository,
                typeRegistry, walletPort, settingsService);
        context.exposeService(PluginShopService.class,
                new PluginShopServiceImpl(catalogService, orderService, typeRegistry));

        ShopWebAssembler assembler = new ShopWebAssembler(typeRegistry, settingsService);
        ShopCatalogFacade catalogFacade = new ShopCatalogFacade(catalogService, settingsService,
                walletPort, assembler, context);
        ShopOrderFacade orderFacade = new ShopOrderFacade(orderService, assembler, context);
        context.registerHttpController(new ShopPlazaController(catalogFacade));
        context.registerHttpController(new ShopUserController(catalogFacade, orderFacade));
        context.registerHttpController(new ShopAdminController(catalogFacade, orderFacade));
    }
}
