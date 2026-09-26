package online.yudream.base.plugin.shop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.shop.api.PluginShopProductType;
import online.yudream.base.plugin.shop.application.cmd.ShopProductSaveCmd;
import online.yudream.base.plugin.shop.application.cmd.ShopVariantCmd;
import online.yudream.base.plugin.shop.application.service.ShopCatalogService;
import online.yudream.base.plugin.shop.application.service.ShopPage;
import online.yudream.base.plugin.shop.application.service.ShopProductTypeRegistry;
import online.yudream.base.plugin.shop.application.service.ShopSettingsService;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.enumerate.ShopProductStatus;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopOrderRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopSettingsRepository;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;
import online.yudream.base.plugin.shop.support.FakeDocumentStore;
import online.yudream.base.plugin.shop.support.FakePluginContext;
import online.yudream.base.plugin.shop.support.FakeWalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 广场按结算方式把两类商品分开：默认口径（玩家市场 SELLER）看不到消耗类商品，
 * 兑换入口（BURN）只看得到消耗类商品；货币筛选项与列表口径一致。
 *
 * <p>结算方式由商品类型处理器推导（GENERIC→SELLER、POINTS_REDEEM→BURN），商品本身不存该字段，
 * 所以这里同时验证了「不新增数据字段也能把两类分开」。
 */
class ShopCatalogServiceTest {

    /** 玩家卖家（自然人社主）。 */
    private static final String SELLER = "2001";
    /** 迁移自积分商城的官方商品归属平台，不是任何自然人卖家。 */
    private static final String PLATFORM_OWNER = "system";

    private ShopProductRepository products;
    private ShopCatalogService service;

    @BeforeEach
    void setUp() {
        FakeDocumentStore documents = new FakeDocumentStore();
        FakeWalletService wallet = new FakeWalletService()
                .setBalance(SELLER, "CNY", "100")
                .setBalance(SELLER, "POINT", "100");
        products = new ShopProductRepository(documents);
        ShopWalletPort walletPort = ShopWalletPort.create(FakePluginContext.withWallet(wallet));
        ShopProductTypeRegistry typeRegistry = new ShopProductTypeRegistry(FakePluginContext.withWallet(wallet));
        service = new ShopCatalogService(products, new ShopOrderRepository(documents), typeRegistry, walletPort,
                new ShopSettingsService(new ShopSettingsRepository(documents), walletPort));
    }

    @Test
    void plazaSeparatesPlayerMarketFromOfficialRedemption() {
        products.save(playerProduct("玩家卖的皮肤", "CNY"));
        products.save(redeemProduct("官方限量徽章", "POINT"));

        assertEquals(List.of("玩家卖的皮肤"), titles(service.pagePlaza(null, null, ShopSettlement.SELLER, 1, 50)),
                "玩家市场只列转账给卖家的商品");
        assertEquals(List.of("官方限量徽章"), titles(service.pagePlaza(null, null, ShopSettlement.BURN, 1, 50)),
                "兑换入口只列消耗类商品");
        assertEquals(2, titles(service.pagePlaza(null, null, null, 1, 50)).size(),
                "不过滤时两类都返回（管理端与兼容调用）");

        assertEquals(List.of("CNY"), currencyCodes(ShopSettlement.SELLER));
        assertEquals(List.of("POINT"), currencyCodes(ShopSettlement.BURN));
    }

    @Test
    void offShelfProductsStayHiddenOnBothSides() {
        products.save(products.save(playerProduct("下架的玩家商品", "CNY")).shelf(ShopProductStatus.OFF_SHELF));
        products.save(products.save(redeemProduct("下架的兑换商品", "POINT")).shelf(ShopProductStatus.OFF_SHELF));

        assertEquals(List.of(), titles(service.pagePlaza(null, null, ShopSettlement.SELLER, 1, 50)));
        assertEquals(List.of(), titles(service.pagePlaza(null, null, ShopSettlement.BURN, 1, 50)));
        assertEquals(List.of(), currencyCodes(ShopSettlement.SELLER));
        assertEquals(List.of(), currencyCodes(ShopSettlement.BURN));
    }

    @Test
    void keywordAndAssetFiltersStillApplyWithinEachSide() {
        products.save(playerProduct("玩家卖的皮肤", "CNY"));
        products.save(playerProduct("玩家卖的坐骑", "POINT"));
        products.save(redeemProduct("官方限量徽章", "POINT"));

        assertEquals(List.of("玩家卖的皮肤"),
                titles(service.pagePlaza(null, "CNY", ShopSettlement.SELLER, 1, 50)));
        assertEquals(List.of("玩家卖的坐骑"),
                titles(service.pagePlaza("坐骑", null, ShopSettlement.SELLER, 1, 50)));
        assertEquals(List.of("官方限量徽章"),
                titles(service.pagePlaza("限量", "POINT", ShopSettlement.BURN, 1, 50)));
    }

    private List<String> titles(ShopPage<ShopProduct> page) {
        return page.records().stream().map(ShopProduct::title).toList();
    }

    /**
     * 发布入口按账号角色分流：管理账号只能投放官方积分商品，普通账号只能发布玩家商品。
     * 这样官方商品不会挂在某个自然人名下，玩家市场也不会出现管理员投放的商品。
     */
    @Test
    void publishEntryIsRestrictedByAccountRole() {
        IllegalArgumentException redeemByPlayer = assertThrows(IllegalArgumentException.class,
                () -> service.saveMyProduct(SELLER, false,
                        cmd(null, "玩家想发兑换", "POINT", ShopProductTypeRegistry.POINTS_REDEEM_TYPE)));
        assertTrue(redeemByPlayer.getMessage().contains("积分兑换商品由管理员发布"),
                "普通账号发布积分兑换应被拒绝：" + redeemByPlayer.getMessage());

        IllegalArgumentException genericByAdmin = assertThrows(IllegalArgumentException.class,
                () -> service.saveMyProduct("9001", true,
                        cmd(null, "管理员想发普通商品", "CNY", ShopProductTypeRegistry.GENERIC_TYPE)));
        assertTrue(genericByAdmin.getMessage().contains("管理员账号只能发布积分兑换商品"),
                "管理账号从「我的商品」发布普通商品应被拒绝：" + genericByAdmin.getMessage());

        ShopProduct published = service.saveMyProduct(SELLER, false,
                cmd(null, "玩家卖的皮肤", "CNY", ShopProductTypeRegistry.GENERIC_TYPE));
        assertEquals(SELLER, published.ownerId(), "玩家商品归属发布者本人");
    }

    /** 管理端投放的官方商品没有归属用户，统一记到平台名下；管理端不能投放玩家侧商品。 */
    @Test
    void adminPublishedOfficialProductHasNoOwner() {
        ShopProduct product = service.adminSaveProduct(
                cmd(null, "官方限量徽章", "POINT", ShopProductTypeRegistry.POINTS_REDEEM_TYPE));
        assertEquals(ShopProduct.PLATFORM_OWNER, product.ownerId(), "官方商品归属平台，没有归属用户");
        assertTrue(product.platformOwned());

        IllegalArgumentException sellerByAdmin = assertThrows(IllegalArgumentException.class,
                () -> service.adminSaveProduct(
                        cmd(null, "官方想发普通商品", "CNY", ShopProductTypeRegistry.GENERIC_TYPE)));
        assertTrue(sellerByAdmin.getMessage().contains("管理员只能发布积分兑换商品"),
                "管理端投放普通商品应被拒绝：" + sellerByAdmin.getMessage());
    }

    /** 商品所属的族创建后不可切换：否则要么给官方商品凭空造出归属用户，要么玩家商品变成无主商品。 */
    @Test
    void productFamilyCannotBeSwitchedOnEdit() {
        ShopProduct player = service.saveMyProduct(SELLER, false,
                cmd(null, "玩家卖的皮肤", "CNY", ShopProductTypeRegistry.GENERIC_TYPE));

        assertThrows(IllegalArgumentException.class, () -> service.saveMyProduct(SELLER, false,
                cmd(player.id(), "偷偷改成兑换", "POINT", ShopProductTypeRegistry.POINTS_REDEEM_TYPE)));
        assertThrows(IllegalArgumentException.class, () -> service.adminSaveProduct(
                cmd(player.id(), "管理员改成兑换", "POINT", ShopProductTypeRegistry.POINTS_REDEEM_TYPE)));
        assertEquals(ShopProductTypeRegistry.GENERIC_TYPE,
                service.adminProductDetail(player.id()).type(), "被拒绝的编辑不落库");
    }

    /** 两个入口的类型选项各自只提供本侧类型。 */
    @Test
    void publishTypeOptionsAreScopedBySettlement() {
        assertEquals(List.of(ShopProductTypeRegistry.GENERIC_TYPE),
                service.typesFor(ShopSettlement.SELLER).stream().map(PluginShopProductType::type).toList());
        assertEquals(List.of(ShopProductTypeRegistry.POINTS_REDEEM_TYPE),
                service.typesFor(ShopSettlement.BURN).stream().map(PluginShopProductType::type).toList());
    }

    /** 型号随商品一起保存并回读：商品价取型号最低价、库存取合计。 */
    @Test
    void variantsArePersistedWithDerivedPriceAndStock() {
        ShopProduct saved = service.saveMyProduct(SELLER, false, new ShopProductSaveCmd(null, "冰箱贴", null, "",
                List.of(), "POINT", new BigDecimal("1000"), -1, 0, ShopProductTypeRegistry.GENERIC_TYPE, Map.of(),
                List.of(new ShopVariantCmd(null, "西瓜", new BigDecimal("1000"), 3, null),
                        new ShopVariantCmd(null, "钻石", new BigDecimal("1500"), 2, null)), null));

        ShopProduct reloaded = products.findById(saved.id()).orElseThrow();
        assertEquals(2, reloaded.variants().size());
        assertEquals(new BigDecimal("1000"), reloaded.price());
        assertEquals(5, reloaded.stock());
        assertEquals("西瓜", reloaded.variants().get(0).name());
        assertNotNull(reloaded.variants().get(1).id(), "新建型号由领域生成 id");
    }

    /** 型号校验：重复名称、缺价格、超量都拒绝。 */
    @Test
    void invalidVariantsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.saveMyProduct(SELLER, false,
                variantCmd(new ShopVariantCmd(null, "西瓜", new BigDecimal("10"), 1, null),
                        new ShopVariantCmd(null, " 西瓜 ", new BigDecimal("10"), 1, null))));
        assertThrows(IllegalArgumentException.class, () -> service.saveMyProduct(SELLER, false,
                variantCmd(new ShopVariantCmd(null, "西瓜", null, 1, null))));
        assertThrows(IllegalArgumentException.class, () -> service.saveMyProduct(SELLER, false,
                variantCmd(new ShopVariantCmd(null, "西瓜", new BigDecimal("10"), 1, "/api/files/x.png"),
                        new ShopVariantCmd(null, "钻石", new BigDecimal("10"), 1, "http://evil/x.png"))));
    }

    private ShopProductSaveCmd variantCmd(ShopVariantCmd... variants) {
        return new ShopProductSaveCmd(null, "冰箱贴", null, "", List.of(), "POINT", new BigDecimal("1000"), -1, 0,
                ShopProductTypeRegistry.GENERIC_TYPE, Map.of(), List.of(variants), null);
    }

    /** 官方商品按排序权重展示：权重大的在前，同权重按创建时间倒序。 */
    @Test
    void officialProductsFollowSortOrder() {
        service.adminSaveProduct(cmdWithSort("默认权重", null));
        service.adminSaveProduct(cmdWithSort("排在最后", 1));
        service.adminSaveProduct(cmdWithSort("排在最前", 99));

        assertEquals(List.of("排在最前", "排在最后", "默认权重"),
                titles(service.pagePlaza(null, null, ShopSettlement.BURN, 1, 50)));
    }

    /** 管理端排序：首次使用时权重全为 0 也能上移/置顶，且只在同族内调整。 */
    @Test
    void adminReorderWorksWithinTheSameFamily() throws InterruptedException {
        products.save(playerProduct("玩家商品", "CNY"));
        // 同权重时按创建时间倒序，休眠保证三次创建落在不同毫秒（否则同毫秒内顺序不稳定）
        ShopProduct first = service.adminSaveProduct(cmdWithSort("官方一号", null));
        Thread.sleep(5);
        service.adminSaveProduct(cmdWithSort("官方二号", null));
        Thread.sleep(5);
        service.adminSaveProduct(cmdWithSort("官方三号", null));

        assertEquals(List.of("官方三号", "官方二号", "官方一号"),
                titles(service.pagePlaza(null, null, ShopSettlement.BURN, 1, 50)), "同权重时按创建时间倒序");

        service.adminMoveProduct(first.id(), "TOP");
        assertEquals(List.of("官方一号", "官方三号", "官方二号"),
                titles(service.pagePlaza(null, null, ShopSettlement.BURN, 1, 50)), "置顶后排在第一位");
        assertEquals(List.of("玩家商品"),
                titles(service.pagePlaza(null, null, ShopSettlement.SELLER, 1, 50)), "玩家市场不受影响");

        ShopProduct second = service.pagePlaza(null, null, ShopSettlement.BURN, 1, 50).records().get(1);
        service.adminMoveProduct(second.id(), "UP");
        assertEquals(second.id(), service.pagePlaza(null, null, ShopSettlement.BURN, 1, 50).records().get(0).id());

        service.adminMoveProduct(second.id(), "DOWN");
        assertEquals(second.id(), service.pagePlaza(null, null, ShopSettlement.BURN, 1, 50).records().get(1).id());
    }

    /** 排序权重只能由管理端设置：玩家发布/编辑不会影响排列。 */
    @Test
    void playerPublishCannotChangeSortOrder() {
        ShopProduct published = service.saveMyProduct(SELLER, false, new ShopProductSaveCmd(null, "玩家商品", null, "",
                List.of(), "CNY", new BigDecimal("10"), -1, 0, ShopProductTypeRegistry.GENERIC_TYPE, Map.of(),
                List.of(), 99));

        assertEquals(ShopProduct.DEFAULT_SORT_ORDER, published.sortOrder());
    }

    private ShopProductSaveCmd cmd(String id, String title, String assetCode, String type) {
        return new ShopProductSaveCmd(id, title, null, "", List.of(), assetCode, new BigDecimal("10"), -1, 0, type,
                Map.of(), List.of(), null);
    }

    private ShopProductSaveCmd cmdWithSort(String title, Integer sortOrder) {
        return new ShopProductSaveCmd(null, title, null, "", List.of(), "POINT", new BigDecimal("10"), -1, 0,
                ShopProductTypeRegistry.POINTS_REDEEM_TYPE, Map.of(), List.of(), sortOrder);
    }

    private List<String> currencyCodes(ShopSettlement settlement) {
        return service.plazaCurrencies(settlement).stream().map(ShopWalletPort.WalletAsset::code).toList();
    }

    private ShopProduct playerProduct(String title, String assetCode) {
        return ShopProduct.create(SELLER, title, null, "", List.of(), assetCode, new BigDecimal("10"), -1, 0,
                ShopProductTypeRegistry.GENERIC_TYPE, Map.of());
    }

    private ShopProduct redeemProduct(String title, String assetCode) {
        return ShopProduct.create(PLATFORM_OWNER, title, null, "", List.of(), assetCode, new BigDecimal("120"), -1, 3,
                ShopProductTypeRegistry.POINTS_REDEEM_TYPE, Map.of());
    }
}
