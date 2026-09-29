package online.yudream.base.plugin.shop.interfaces.http;

import online.yudream.base.plugin.shop.application.service.ShopCatalogService;
import online.yudream.base.plugin.shop.application.service.ShopPage;
import online.yudream.base.plugin.shop.application.service.ShopSettingsService;
import online.yudream.base.plugin.shop.bootstrap.ShopPlugin;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import online.yudream.base.plugin.shop.domain.enumerate.ShopTradeFeePayee;
import online.yudream.base.plugin.shop.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;
import online.yudream.base.plugin.shop.interfaces.assembler.ShopWebAssembler;
import online.yudream.base.plugin.shop.interfaces.request.ShopAdminProductSaveRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopProductSaveRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopSettingsSaveRequest;
import online.yudream.base.plugin.shop.interfaces.request.ShopSortRequest;
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
import java.util.Locale;
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
    private final ShopWalletPort walletPort;
    private final ShopWebAssembler assembler;
    private final PluginContext context;

    public ShopCatalogFacade(ShopCatalogService catalogService, ShopSettingsService settingsService,
                             ShopWalletPort walletPort,
                             ShopWebAssembler assembler, PluginContext context) {
        this.catalogService = catalogService;
        this.settingsService = settingsService;
        this.walletPort = walletPort;
        this.assembler = assembler;
        this.context = context;
    }

    // ---------- 广场 ----------

    public PluginHttpResponse plazaProducts(PluginHttpRequest request) {
        ShopPage<ShopProduct> result = catalogService.pagePlaza(
                firstQuery(request, "keyword"), firstQuery(request, "assetCode"), plazaSettlement(request),
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

    /** 广场货币筛选项：与商品列表同一结算方式，避免「玩家市场」里出现只有兑换商品在用的货币。 */
    public PluginHttpResponse plazaCurrencies(PluginHttpRequest request) {
        return PluginHttpResponse.ok(
                catalogService.plazaCurrencies(plazaSettlement(request)).stream().map(assembler::toRes).toList());
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

    /** 用户端只提供玩家侧类型：官方消耗类商品没有归属用户，由管理员在管理端投放。 */
    public PluginHttpResponse myProductTypes() {
        return PluginHttpResponse.ok(catalogService.typesFor(ShopSettlement.SELLER).stream()
                .map(assembler::toRes).toList());
    }

    /**
     * 当前账号的上架资格：开关关闭、积分门槛不足或账号是管理员（只能发官方商品）时
     * allowed=false 并给出原因，用户端据此禁用发布入口。
     */
    public PluginHttpResponse publishQualification(PluginHttpRequest request) {
        String userId = requireUserId(request);
        if (canManage(request)) {
            return PluginHttpResponse.ok(new ShopPublishQualificationRes(false,
                    "管理员账号只能发布积分兑换商品，请在「商店管理 → 商品管理」新增官方商品",
                    null, null, null, walletPort.available()));
        }
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
        ShopProduct product = catalogService.saveMyProduct(userId, canManage(request), assembler.toCmd(body));
        return PluginHttpResponse.ok(assembler.toRes(product, null,
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, true));
    }

    public PluginHttpResponse updateProduct(PluginHttpRequest request) {
        String userId = requireUserId(request);
        ShopProductSaveRequest body = JsonSupport.read(request.body(), ShopProductSaveRequest.class);
        ShopProduct product = catalogService.saveMyProduct(userId, canManage(request),
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

    /** 管理端排序：在同族的展示顺序里上移、下移或置顶。 */
    public PluginHttpResponse adminMoveProduct(PluginHttpRequest request) {
        ShopSortRequest body = JsonSupport.read(request.body(), ShopSortRequest.class);
        ShopProduct product = catalogService.adminMoveProduct(pathSegment(request.path(), 2), body.direction());
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
        requireTradeFeePayeeUser(body.tradeFeePayee(), body.tradeFeePayeeUserId());
        ShopSettings saved = settingsService.save(new ShopSettings(
                body.allowUserPublish() == null || body.allowUserPublish(),
                body.publishAssetCode(),
                body.publishMinBalance() == null ? BigDecimal.ZERO : body.publishMinBalance(),
                body.allowedAssetCodes(),
                body.platformOwnerName(),
                body.platformOwnerAvatar(),
                body.tradeFeeEnabled() != null && body.tradeFeeEnabled(),
                decimal(body.tradeFeeRate(), "交易手续费费率"),
                decimal(body.tradeFeeMinAmount(), "最低手续费"),
                ShopTradeFeePayee.from(body.tradeFeePayee()),
                body.tradeFeePayeeUserId()));
        return PluginHttpResponse.ok(toSettingsRes(saved));
    }

    /**
     * 十进制字符串入参 → BigDecimal；空值按 0 处理，非法数字给出中文报错。
     * 金额一律以十进制字符串跨接口传输，避免前端浮点尾数直接落库。
     */
    private BigDecimal decimal(String value, String label) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + "必须是数字：" + value);
        }
    }

    /**
     * 手续费收款方式为「平台用户」时，除了领域层的非空校验，还尽量校验该用户确实存在。
     *
     * <p>宿主用户端口（{@code context.framework().users()}）只在接口层可达，application/domain 不依赖 SPI，
     * 所以存在性校验放在这里做尽力而为的一层：查询失败（端口异常、ID 不是数字、用户已删除）都按「不存在」处理，
     * 给出可直接展示的中文提示。真正决定手续费去向的是订单快照里的用户 ID，保存时校验只是防错。
     */
    private void requireTradeFeePayeeUser(String payee, String userId) {
        if (ShopTradeFeePayee.from(payee) != ShopTradeFeePayee.PLATFORM) {
            return;
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("手续费收款方式选择「平台用户」时，必须填写平台用户 ID");
        }
        String trimmed = userId.trim();
        boolean exists;
        try {
            exists = context.framework().users().findById(Long.parseLong(trimmed)).isPresent();
        } catch (RuntimeException | LinkageError ex) {
            exists = false;
        }
        if (!exists) {
            throw new IllegalArgumentException("手续费收款用户不存在：" + trimmed + "，请通过用户选择器指定有效的平台用户");
        }
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

    /** 管理端商品类型选项：只提供官方侧（消耗类）类型，管理账号可能没有 publish 权限，不能复用 /me 端点。 */
    public PluginHttpResponse adminProductTypes() {
        return PluginHttpResponse.ok(catalogService.typesFor(ShopSettlement.BURN).stream()
                .map(assembler::toRes).toList());
    }

    public PluginHttpResponse adminCreateProduct(PluginHttpRequest request) {
        ShopAdminProductSaveRequest body = JsonSupport.read(request.body(), ShopAdminProductSaveRequest.class);
        ShopProduct product = catalogService.adminSaveProduct(assembler.toCmd(body));
        return PluginHttpResponse.ok(assembler.toRes(product, userOf(product.ownerId()),
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, true));
    }

    public PluginHttpResponse adminUpdateProduct(PluginHttpRequest request) {
        ShopAdminProductSaveRequest body = JsonSupport.read(request.body(), ShopAdminProductSaveRequest.class);
        ShopProduct product = catalogService.adminSaveProduct(assembler.toCmd(pathSegment(request.path(), 2), body));
        return PluginHttpResponse.ok(assembler.toRes(product, userOf(product.ownerId()),
                assetSymbols().getOrDefault(product.assetCode(), "¥"), true, true));
    }

    // ---------- 内部 ----------

    private ShopSettingsRes toSettingsRes(ShopSettings settings) {
        return new ShopSettingsRes(settings.allowUserPublish(), settings.publishAssetCode(),
                settings.publishMinBalance(), settings.allowedAssetCodes(), settings.platformOwnerName(),
                settings.platformOwnerAvatar(),
                settings.tradeFeeEnabled(),
                decimal(settings.tradeFeeRate()),
                decimal(settings.tradeFeeMinAmount()),
                settings.tradeFeePayee() == null ? ShopTradeFeePayee.BURN.name() : settings.tradeFeePayee().name(),
                settings.tradeFeePayeeUserId(),
                walletPort.available(),
                walletPort.enabledAssets().stream().map(assembler::toRes).toList());
    }

    /** 金额出参统一十进制字符串（去掉无意义的尾随 0）。 */
    private String decimal(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * 调用者是否持有商店管理权限。
     *
     * <p>发布规则按它分流：管理员只能投放官方（消耗类）商品，非管理员只能发布玩家侧商品。
     * 与宿主 SPI 对齐，超管角色下发的是字面量 {@code *}，由 {@code hasPermission} 展开。
     */
    private boolean canManage(PluginHttpRequest request) {
        return request.principal() != null && request.principal().hasPermission(ShopPlugin.MANAGE_PERMISSION);
    }

    /**
     * 广场的结算方式过滤。
     *
     * <p>缺省是 {@link ShopSettlement#SELLER}——广场即「玩家市场」，只列玩家互相买卖的商品；
     * 「积分商城」显式传 {@code settlement=BURN}。写了无法识别的值直接报错，
     * 避免参数写错时静默退化成"只看玩家市场"这种难以察觉的错。
     */
    private ShopSettlement plazaSettlement(PluginHttpRequest request) {
        String raw = firstQuery(request, "settlement");
        if (raw == null || raw.isBlank()) {
            return ShopSettlement.SELLER;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "SELLER" -> ShopSettlement.SELLER;
            case "BURN" -> ShopSettlement.BURN;
            default -> throw new IllegalArgumentException("未知的结算方式：" + raw);
        };
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
