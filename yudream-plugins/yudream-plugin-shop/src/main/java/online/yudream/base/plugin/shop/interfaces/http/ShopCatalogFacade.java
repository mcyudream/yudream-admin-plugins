package online.yudream.base.plugin.shop.interfaces.http;

import online.yudream.base.plugin.shop.application.service.ShopCatalogService;
import online.yudream.base.plugin.shop.application.service.ShopPage;
import online.yudream.base.plugin.shop.application.service.ShopProductTypeRegistry;
import online.yudream.base.plugin.shop.application.service.ShopSettingsService;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;
import online.yudream.base.plugin.shop.interfaces.assembler.ShopWebAssembler;
import online.yudream.base.plugin.shop.interfaces.request.ShopAdminProductSaveRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopProductSaveRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopSettingsSaveRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopShelfRequest;
import online.yudream.base.plugin.shop.interfaces.res.ShopProductRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopPublishQualificationRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopSettingsRes;
import online.yudream.base.plugin.shop.interfaces.res.ShopUserRes;
import online.yudream.base.plugin.shop.domain.valobj.ShopSettings;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.user.PluginUserOption;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.firstQuery;
import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.page;
import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.pathSegment;
import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.requireUserId;
import static online.yudream.base.plugin.shop.interfaces.http.HttpSupport.size;

/** 商品目录 HTTP 门面：广场、卖家商品维护、管理员商品治理。 */
public class ShopCatalogFacade {

    private final ShopCatalogService catalogService;
    private final ShopSettingsService settingsService;
    private final ShopProductTypeRegistry typeRegistry;
    private final ShopWalletPort walletPort;
    private final ShopWebAssembler assembler;
    private final PluginContext context;

    public ShopCatalogFacade(ShopCatalogService catalogService, ShopSettingsService settingsService,
                             ShopProductTypeRegistry typeRegistry, ShopWalletPort walletPort,
                             ShopWebAssembler assembler, PluginContext context) {
        this.catalogService = catalogService;
        this.settingsService = settingsService;
        this.typeRegistry = typeRegistry;
        this.walletPort = walletPort;
        this.assembler = assembler;
        this.context = context;
    }

    // ---------- 广场 ----------

    public PluginHttpResponse plazaProducts(PluginHttpRequest request) {
        ShopPage<ShopProduct> result = catalogService.pagePlaza(
                firstQuery(request, "keyword"), firstQuery(request, "assetCode"),
                page(request), size(request));
        Map<String, String> symbols = assetSymbols();
        List<ShopProductRes> records = result.records().stream()
                .map(product -> assembler.toRes(product, userOf(product.ownerId()),
                        symbols.getOrDefault(product.assetCode(), "¥"), false, false))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", result.total()));
    }

    public PluginHttpResponse plazaProductDetail(PluginHttpRequest request) {
        ShopProduct product = catalogService.plazaDetail(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(assembler.toRes(product, userOf(product.ownerId()),
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, false));
    }

    public PluginHttpResponse plazaCurrencies() {
        return PluginHttpResponse.ok(catalogService.plazaCurrencies().stream().map(assembler::toRes).toList());
    }

    // ---------- 卖家商品维护 ----------

    public PluginHttpResponse myCurrencies() {
        ShopSettings settings = settingsService.current();
        return PluginHttpResponse.ok(Map.of(
                "walletAvailable", walletPort.available(),
                "records", walletPort.enabledAssets().stream()
                        .filter(asset -> settings.assetAllowed(asset.code()))
                        .map(assembler::toRes)
                        .toList()));
    }

    public PluginHttpResponse myProductTypes() {
        return PluginHttpResponse.ok(typeRegistry.types().stream().map(assembler::toRes).toList());
    }

    /** 当前账号的上架资格：开关关闭或积分门槛不足时 allowed=false 并给出原因。 */
    public PluginHttpResponse publishQualification(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopSettingsService.PublishQualification qualification = settingsService.qualify(userId);
        return PluginHttpResponse.ok(new ShopPublishQualificationRes(
                qualification.allowed(), qualification.reason(),
                qualification.publishAssetCode(), qualification.publishMinBalance(),
                qualification.balance() == null ? null : qualification.balance().toPlainString(),
                qualification.walletAvailable()));
    }

    public PluginHttpResponse myProducts(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopPage<ShopProduct> result = catalogService.pageMyProducts(userId,
                firstQuery(request, "keyword"), page(request), size(request));
        Map<String, String> symbols = assetSymbols();
        List<ShopProductRes> records = result.records().stream()
                .map(product -> assembler.toRes(product, null,
                        symbols.getOrDefault(product.assetCode(), "¥"), false, false))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", result.total()));
    }

    public PluginHttpResponse myProductDetail(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopProduct product = catalogService.myProductDetail(userId, pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(assembler.toRes(product, null,
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, true));
    }

    public PluginHttpResponse createProduct(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopProductSaveRequest body = JsonSupport.read(request.body(), ShopProductSaveRequest.class);
        ShopProduct product = catalogService.saveMyProduct(userId, assembler.toCmd(body));
        return PluginHttpResponse.ok(assembler.toRes(product, null,
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, true));
    }

    public PluginHttpResponse updateProduct(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopProductSaveRequest body = JsonSupport.read(request.body(), ShopProductSaveRequest.class);
        ShopProduct product = catalogService.saveMyProduct(userId,
                assembler.toCmd(pathSegment(request.path(), 2), body));
        return PluginHttpResponse.ok(assembler.toRes(product, null,
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, true));
    }

    public PluginHttpResponse setMyProductShelf(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopShelfRequest body = JsonSupport.read(request.body(), ShopShelfRequest.class);
        if (body.onShelf() == null) {
            throw new IllegalArgumentException("onShelf 不能为空");
        }
        ShopProduct product = catalogService.setMyProductShelf(userId, pathSegment(request.path(), 2), body.onShelf());
        return PluginHttpResponse.ok(assembler.toRes(product, null,
                assetSymbols().getOrDefault(product.assetCode(), "¥"), false, false));
    }

    public PluginHttpResponse deleteMyProduct(PluginHttpRequest request) {
        String userId = requireUserId(request);
        catalogService.deleteMyProduct(userId, pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    // ---------- 管理员商品治理 ----------

    public PluginHttpResponse adminProducts(PluginHttpRequest request) {
        ShopPage<ShopProduct> result = catalogService.adminPageProducts(
                firstQuery(request, "keyword"), firstQuery(request, "ownerId"),
                firstQuery(request, "status"), firstQuery(request, "type"),
                page(request), size(request));
        Map<String, String> symbols = assetSymbols();
        List<ShopProductRes> records = result.records().stream()
                .map(product -> assembler.toRes(product, userOf(product.ownerId()),
                        symbols.getOrDefault(product.assetCode(), "¥"), false, false))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", result.total()));
    }

    public PluginHttpResponse adminProductDetail(PluginHttpRequest request) {
        ShopProduct product = catalogService.adminProductDetail(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(assembler.toRes(product, userOf(product.ownerId()),
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, true));
    }

    public PluginHttpResponse adminSetShelf(PluginHttpRequest request) {
        ShopShelfRequest body = JsonSupport.read(request.body(), ShopShelfRequest.class);
        if (body.onShelf() == null) {
            throw new IllegalArgumentException("onShelf 不能为空");
        }
        ShopProduct product = catalogService.adminSetShelf(pathSegment(request.path(), 2), body.onShelf());
        return PluginHttpResponse.ok(assembler.toRes(product, userOf(product.ownerId()),
                assetSymbols().getOrDefault(product.assetCode(), "¥"), false, false));
    }

    public PluginHttpResponse adminDeleteProduct(PluginHttpRequest request) {
        catalogService.adminDeleteProduct(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    // ---------- 管理端设置与代上架 ----------

    public PluginHttpResponse adminSettings() {
        return PluginHttpResponse.ok(toSettingsRes(settingsService.current()));
    }

    public PluginHttpResponse saveAdminSettings(PluginHttpRequest request) {
        ShopSettingsSaveRequest body = JsonSupport.read(request.body(), ShopSettingsSaveRequest.class);
        ShopSettings saved = settingsService.save(new ShopSettings(
                body.allowUserPublish() == null || body.allowUserPublish(),
                body.publishAssetCode(),
                body.publishMinBalance() == null ? BigDecimal.ZERO : body.publishMinBalance(),
                body.allowedAssetCodes()));
        return PluginHttpResponse.ok(toSettingsRes(saved));
    }

    /** 管理端用户选项（归属用户选择器取数）：searchUsers 1-based 分页且无总数，用 hasMore 兜底。 */
    public PluginHttpResponse adminUserOptions(PluginHttpRequest request) {
        int pageNo = page(request);
        int pageSize = size(request);
        List<PluginUserOption> users = context.framework().users()
                .searchUsers(firstQuery(request, "keyword"), null, pageNo, pageSize);
        long total = (long) (pageNo - 1) * pageSize + users.size() + (users.size() >= pageSize ? 1 : 0);
        List<ShopUserRes> records = users.stream()
                .map(user -> new ShopUserRes(user.id(), user.username(), user.nickname(), user.avatar()))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", total));
    }

    /** 管理端商品类型选项：管理账号可能没有 publish 权限，不能复用 /me 端点。 */
    public PluginHttpResponse adminProductTypes() {
        return PluginHttpResponse.ok(typeRegistry.types().stream().map(assembler::toRes).toList());
    }

    public PluginHttpResponse adminCreateProduct(PluginHttpRequest request) {
        String operatorId = requireUserId(request);
        ShopAdminProductSaveRequest body = JsonSupport.read(request.body(), ShopAdminProductSaveRequest.class);
        ShopProduct product = catalogService.adminSaveProduct(operatorId, requireTargetOwner(body.ownerId()),
                assembler.toCmd(body));
        return PluginHttpResponse.ok(assembler.toRes(product, userOf(product.ownerId()),
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, true));
    }

    public PluginHttpResponse adminUpdateProduct(PluginHttpRequest request) {
        String operatorId = requireUserId(request);
        ShopAdminProductSaveRequest body = JsonSupport.read(request.body(), ShopAdminProductSaveRequest.class);
        ShopProduct product = catalogService.adminSaveProduct(operatorId, requireTargetOwner(body.ownerId()),
                assembler.toCmd(pathSegment(request.path(), 2), body));
        return PluginHttpResponse.ok(assembler.toRes(product, userOf(product.ownerId()),
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, true));
    }

    // ---------- 内部 ----------

    private ShopSettingsRes toSettingsRes(ShopSettings settings) {
        return new ShopSettingsRes(settings.allowUserPublish(), settings.publishAssetCode(),
                settings.publishMinBalance(), settings.allowedAssetCodes(), walletPort.available(),
                walletPort.enabledAssets().stream().map(assembler::toRes).toList());
    }

    /** 归属用户校验：填了 ownerId 必须是平台存在的用户，避免手误造出无主商品。 */
    private String requireTargetOwner(String ownerId) {
        String target = ownerId == null || ownerId.isBlank() ? null : ownerId.trim();
        if (target != null && userOf(target) == null) {
            throw new IllegalArgumentException("归属用户不存在：" + target);
        }
        return target;
    }

    /** 每个请求解析一次货币符号表，避免分页列表逐商品查询钱包。 */
    private Map<String, String> assetSymbols() {
        Map<String, String> symbols = new LinkedHashMap<>();
        for (ShopWalletPort.WalletAsset asset : walletPort.enabledAssets()) {
            symbols.put(asset.code(), asset.symbol());
        }
        return symbols;
    }

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
