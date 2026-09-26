package online.yudream.base.plugin.shop.application.service;

import online.yudream.base.plugin.shop.api.PluginShopProduct;
import online.yudream.base.plugin.shop.api.PluginShopProductType;
import online.yudream.base.plugin.shop.application.cmd.ShopProductSaveCmd;
import online.yudream.base.plugin.shop.application.cmd.ShopVariantCmd;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.aggregate.ShopVariant;
import online.yudream.base.plugin.shop.domain.enumerate.ShopProductStatus;
import online.yudream.base.plugin.shop.domain.enumerate.ShopSettlement;
import online.yudream.base.plugin.shop.domain.valobj.ShopSettings;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopOrderRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 商品目录用例：广场浏览、卖家商品维护、管理员商品治理。
 */
public class ShopCatalogService {

    private static final int MAX_IMAGES = 9;
    private static final int MAX_DESCRIPTION_LENGTH = 20000;
    private static final int MAX_SUMMARY_LENGTH = 200;

    private final ShopProductRepository productRepository;
    private final ShopOrderRepository orderRepository;
    private final ShopProductTypeRegistry typeRegistry;
    private final ShopWalletPort walletPort;
    private final ShopSettingsService settingsService;

    public ShopCatalogService(ShopProductRepository productRepository, ShopOrderRepository orderRepository,
                              ShopProductTypeRegistry typeRegistry, ShopWalletPort walletPort,
                              ShopSettingsService settingsService) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.typeRegistry = typeRegistry;
        this.walletPort = walletPort;
        this.settingsService = settingsService;
    }

    // ---------- 广场（view 权限，仅上架商品） ----------

    /**
     * 广场浏览，按结算方式把两类商品分开呈现。
     *
     * <p>{@code settlement} 为 null 表示不过滤（管理端或兼容调用）；用户端两个入口各自传值：
     * 「玩家市场」传 {@link ShopSettlement#SELLER}，「积分商城」传 {@link ShopSettlement#BURN}。
     * 结算方式由商品类型处理器决定（{@link ShopProductTypeHandler#settlement()}），不额外存字段，
     * 因此历史商品与以后新增的商品类型都会自动归到正确的一侧。
     */
    public ShopPage<ShopProduct> pagePlaza(String keyword, String assetCode, ShopSettlement settlement,
                                           int page, int size) {
        List<ShopProduct> filtered = productRepository.findAll().stream()
                .filter(ShopProduct::onShelf)
                .filter(product -> settlement == null || settlement == settlementOf(product))
                .filter(product -> !hasText(assetCode) || assetCode.trim().equalsIgnoreCase(product.assetCode()))
                .filter(product -> matchesKeyword(product, keyword))
                .sorted(displayOrder())
                .toList();
        return ShopPage.of(filtered, page, size);
    }

    /**
     * 广场与管理列表的排序：自定义排序权重（大的在前）优先，同权重按创建时间倒序。
     * 玩家商品权重恒为 0，因此玩家市场的顺序与改动前一致。
     */
    private static Comparator<ShopProduct> displayOrder() {
        return Comparator.comparingInt(ShopProduct::sortOrder).reversed()
                .thenComparing(Comparator.comparingLong(ShopProduct::createdAt).reversed());
    }

    public ShopProduct plazaDetail(String productId) {
        return productRepository.findById(productId)
                .filter(ShopProduct::onShelf)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在或已下架"));
    }

    /** 广场货币筛选项：该结算方式下上架商品实际用到的货币，有钱包时附带名称与符号。 */
    public List<ShopWalletPort.WalletAsset> plazaCurrencies(ShopSettlement settlement) {
        Set<String> codes = new LinkedHashSet<>();
        productRepository.findAll().stream()
                .filter(ShopProduct::onShelf)
                .filter(product -> settlement == null || settlement == settlementOf(product))
                .forEach(product -> codes.add(product.assetCode()));
        Map<String, ShopWalletPort.WalletAsset> walletAssets = new LinkedHashMap<>();
        walletPort.enabledAssets().forEach(asset -> walletAssets.put(asset.code(), asset));
        return codes.stream()
                .map(code -> walletAssets.getOrDefault(code, new ShopWalletPort.WalletAsset(code, code, "¥", 2)))
                .toList();
    }

    /** 商品的结算方式：由已注册的类型处理器决定；类型未注册（提供方插件停用）时按 SELLER 处理。 */
    private ShopSettlement settlementOf(ShopProduct product) {
        return typeRegistry.settlementOf(product.type());
    }

    /** 商品类型对应的结算方式（发布校验用）。 */
    public ShopSettlement settlementOfType(String type) {
        return typeRegistry.settlementOf(type);
    }

    /** 某结算方式下可发布的商品类型：用户端只给玩家侧，管理端只给官方侧。 */
    public List<PluginShopProductType> typesFor(ShopSettlement settlement) {
        return typeRegistry.types(settlement);
    }

    // ---------- 卖家商品维护（publish 权限，仅本人商品） ----------

    public ShopPage<ShopProduct> pageMyProducts(String ownerId, String keyword, int page, int size) {
        List<ShopProduct> filtered = productRepository.findAll().stream()
                .filter(product -> product.ownerId().equals(ownerId))
                .filter(product -> matchesKeyword(product, keyword))
                .sorted(Comparator.comparingLong(ShopProduct::updatedAt).reversed())
                .toList();
        return ShopPage.of(filtered, page, size);
    }

    public ShopProduct myProductDetail(String ownerId, String productId) {
        return requireOwnedProduct(ownerId, productId);
    }

    /**
     * 卖家自助发布/编辑商品：只能发布玩家侧（{@link ShopSettlement#SELLER}）商品，归属固定为发布者本人。
     *
     * <p>官方侧的消耗类商品（如积分兑换）没有归属用户，属于管理员投放，不能从「我的商品」入口创建；
     * 但管理员账号持有的历史商品仍可照常编辑。
     */
    public ShopProduct saveMyProduct(String ownerId, boolean canManage, ShopProductSaveCmd cmd) {
        boolean creating = cmd.id() == null || cmd.id().isBlank();
        ShopSettlement settlement = settlementOfType(requireText(cmd.type(), "商品类型不能为空").trim());
        if (creating) {
            if (canManage) {
                throw new IllegalArgumentException("管理员账号只能发布积分兑换商品，请在「商店管理 → 商品管理」新增官方商品");
            }
            requireSettlement(settlement, ShopSettlement.SELLER, "普通账号只能发布普通商品，积分兑换商品由管理员发布");
        }
        requireUserPublishAllowed(ownerId, creating);
        if (creating) {
            return doSave(ownerId, cmd, ShopProduct.DEFAULT_SORT_ORDER);
        }
        ShopProduct existing = requireOwnedProduct(ownerId, cmd.id());
        requireSameSettlement(existing, settlement);
        // 排序权重仅管理端可改：玩家编辑自己的商品不会影响排列
        return doSave(existing.ownerId(), cmd, existing.sortOrder());
    }

    public ShopProduct setMyProductShelf(String ownerId, String productId, boolean onShelf) {
        ShopProduct product = requireOwnedProduct(ownerId, productId);
        if (onShelf && !product.onShelf()) {
            requireUserPublishAllowed(ownerId, true);
        }
        return productRepository.save(product.shelf(onShelf ? ShopProductStatus.ON_SHELF : ShopProductStatus.OFF_SHELF));
    }

    public void deleteMyProduct(String ownerId, String productId) {
        ShopProduct product = requireOwnedProduct(ownerId, productId);
        requireNoDeliveringOrders(product.id());
        productRepository.delete(product.id());
    }

    // ---------- 管理员商品治理（manage 权限，跨用户） ----------

    public ShopPage<ShopProduct> adminPageProducts(String keyword, String ownerId, String status, String type,
                                                   int page, int size) {
        List<ShopProduct> filtered = productRepository.findAll().stream()
                .filter(product -> !hasText(ownerId) || product.ownerId().equals(ownerId.trim()))
                .filter(product -> !hasText(status) || product.status().name().equalsIgnoreCase(status.trim()))
                .filter(product -> !hasText(type) || product.type().equalsIgnoreCase(type.trim()))
                .filter(product -> matchesKeyword(product, keyword))
                .sorted(displayOrder())
                .toList();
        return ShopPage.of(filtered, page, size);
    }

    /**
     * 管理端排序：在同一结算方式（玩家侧 / 官方侧）内上移、下移或置顶。
     *
     * <p>先把该族权重按当前顺序归一化成递减整数，再交换/移动目标，最后整族回写，
     * 因此首次使用时即使所有商品权重都是 0 也能正确排序。
     */
    public ShopProduct adminMoveProduct(String productId, String direction) {
        ShopProduct target = adminProductDetail(productId);
        ShopSettlement settlement = settlementOf(target);
        List<ShopProduct> family = new ArrayList<>(productRepository.findAll().stream()
                .filter(product -> settlementOf(product) == settlement)
                .sorted(displayOrder())
                .toList());
        int index = -1;
        for (int i = 0; i < family.size(); i++) {
            if (family.get(i).id().equals(target.id())) {
                index = i;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("商品不存在");
        }
        int nextIndex = switch (direction == null ? "" : direction.trim().toUpperCase(Locale.ROOT)) {
            case "UP" -> Math.max(0, index - 1);
            case "DOWN" -> Math.min(family.size() - 1, index + 1);
            case "TOP" -> 0;
            default -> throw new IllegalArgumentException("未知的排序方向：" + direction);
        };
        if (nextIndex != index) {
            family.add(nextIndex, family.remove(index));
        }
        ShopProduct moved = null;
        for (int i = 0; i < family.size(); i++) {
            ShopProduct product = family.get(i).withSortOrder(family.size() - i);
            ShopProduct saved = productRepository.save(product);
            if (i == nextIndex) {
                moved = saved;
            }
        }
        return moved == null ? target : moved;
    }

    public ShopProduct adminProductDetail(String productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));
    }

    public ShopProduct adminSetShelf(String productId, boolean onShelf) {
        ShopProduct product = adminProductDetail(productId);
        return productRepository.save(product.shelf(onShelf ? ShopProductStatus.ON_SHELF : ShopProductStatus.OFF_SHELF));
    }

    public void adminDeleteProduct(String productId) {
        ShopProduct product = adminProductDetail(productId);
        requireNoDeliveringOrders(product.id());
        productRepository.delete(product.id());
    }

    /**
     * 管理员新增/编辑商品：只能投放官方侧（{@link ShopSettlement#BURN}）商品，归属平台、没有归属用户。
     *
     * <p>历史遗留的玩家侧商品（管理员在旧版本代上架的）仍可编辑，归属保持不变；
     * 商品一旦创建就不能在玩家侧与官方侧之间切换（类型族变化会被拒绝）。
     */
    public ShopProduct adminSaveProduct(ShopProductSaveCmd cmd) {
        boolean creating = cmd.id() == null || cmd.id().isBlank();
        ShopSettlement settlement = settlementOfType(requireText(cmd.type(), "商品类型不能为空").trim());
        if (creating) {
            requireSettlement(settlement, ShopSettlement.BURN, "管理员只能发布积分兑换商品，普通商品请由玩家自行上架");
            return doSave(ShopProduct.PLATFORM_OWNER, cmd, requestedSortOrder(cmd, ShopProduct.DEFAULT_SORT_ORDER));
        }
        ShopProduct existing = adminProductDetail(cmd.id());
        requireSameSettlement(existing, settlement);
        return doSave(existing.ownerId(), cmd, requestedSortOrder(cmd, existing.sortOrder()));
    }

    /** 管理端传入的排序权重；缺省沿用原值。 */
    private int requestedSortOrder(ShopProductSaveCmd cmd, int fallback) {
        return cmd.sortOrder() == null ? fallback : cmd.sortOrder();
    }

    // ---------- 跨插件视图 ----------

    public Optional<PluginShopProduct> findProduct(String productId) {
        return productRepository.findById(productId).map(this::toSpi);
    }

    private PluginShopProduct toSpi(ShopProduct product) {
        return new PluginShopProduct(product.id(), product.ownerId(), product.title(), product.type(),
                product.assetCode(), product.price(), product.stock(), product.soldCount(),
                product.status().name(), product.createdAt());
    }

    // ---------- 内部 ----------

    /** 用户发布/上架前置校验：上架开关约束所有写操作；积分门槛约束新建与重新上架。 */
    private void requireUserPublishAllowed(String ownerId, boolean creating) {
        ShopSettings settings = settingsService.current();
        if (!settings.allowUserPublish()) {
            throw new IllegalArgumentException("管理员已关闭用户上架功能，暂时无法发布或编辑商品");
        }
        if (creating && settings.requiresBalance()) {
            requireBalanceAtLeast(ownerId, settings);
        }
    }

    private void requireBalanceAtLeast(String ownerId, ShopSettings settings) {
        if (!walletPort.available()) {
            throw new IllegalArgumentException("钱包插件不可用，无法校验上架积分门槛");
        }
        BigDecimal balance = walletPort.balance(ownerId, settings.publishAssetCode()).orElse(null);
        if (balance == null) {
            throw new IllegalArgumentException("暂时无法查询钱包余额，请稍后重试");
        }
        if (balance.compareTo(settings.publishMinBalance()) < 0) {
            throw new IllegalArgumentException(String.format("上架商品需要至少 %s %s，当前余额 %s",
                    settings.publishMinBalance().stripTrailingZeros().toPlainString(),
                    settings.publishAssetCode(),
                    balance.stripTrailingZeros().toPlainString()));
        }
    }

    /** 发布入口的结算方式限制：玩家侧入口只能发普通商品，管理端只能发官方消耗商品。 */
    private void requireSettlement(ShopSettlement actual, ShopSettlement expected, String message) {
        if (actual != expected) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 商品所属的族（玩家侧 / 官方侧）在创建时确定，之后不允许切换：
     * 否则要么给官方商品凭空造出归属用户，要么让玩家商品变成没有归属的平台商品。
     */
    private void requireSameSettlement(ShopProduct existing, ShopSettlement next) {
        if (settlementOf(existing) != next) {
            throw new IllegalArgumentException("商品类型不能在普通商品与积分兑换之间切换，请新建对应类型的商品");
        }
    }

    private ShopProduct doSave(String ownerId, ShopProductSaveCmd cmd, int sortOrder) {
        String type = requireText(cmd.type(), "商品类型不能为空").trim();
        Map<String, Object> typeConfig = typeRegistry.normalizeConfig(type, cmd.typeConfig());
        String assetCode = normalizeAssetCode(cmd.assetCode());
        if (walletPort.available() && !walletPort.assetEnabled(assetCode)) {
            throw new IllegalArgumentException("货币不存在或已停用：" + assetCode);
        }
        if (!settingsService.current().assetAllowed(assetCode)) {
            throw new IllegalArgumentException("该货币不允许用于上架交易：" + assetCode + "，请更换货币或联系管理员调整商店设置");
        }
        List<String> images = normalizeImages(cmd.images());
        String description = cmd.descriptionMd() == null ? "" : cmd.descriptionMd();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("商品详情不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        }
        String summary = cmd.summary();
        if (hasText(summary) && summary.trim().length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("商品简介不能超过 " + MAX_SUMMARY_LENGTH + " 个字符");
        }
        int stock = cmd.stock();
        int perUserLimit = cmd.perUserLimit();
        BigDecimal price = cmd.price();
        List<ShopVariant> variants = normalizeVariants(cmd.variants());
        if (cmd.id() == null || cmd.id().isBlank()) {
            return productRepository.save(ShopProduct.create(ownerId, cmd.title(), summary, description,
                    images, assetCode, price, stock, perUserLimit, variants, type, typeConfig, sortOrder));
        }
        ShopProduct existing = requireOwnedProduct(ownerId, cmd.id());
        return productRepository.save(existing.update(cmd.title(), summary, description, images, assetCode,
                price, stock, perUserLimit, variants, type, typeConfig, sortOrder));
    }

    /**
     * 型号规范化：名称为空、价格非法、库存越界、名称重复、图片非法或数量超限都直接拒绝；
     * id 为空表示新建型号（由领域生成），已有 id 保留，便于订单快照继续对得上。
     */
    private List<ShopVariant> normalizeVariants(List<ShopVariantCmd> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        List<ShopVariantCmd> present = commands.stream()
                .filter(command -> command != null && (hasText(command.name()) || command.price() != null))
                .toList();
        if (present.isEmpty()) {
            return List.of();
        }
        if (present.size() > ShopVariant.MAX_VARIANTS) {
            throw new IllegalArgumentException("商品型号最多 " + ShopVariant.MAX_VARIANTS + " 个");
        }
        List<ShopVariant> variants = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (ShopVariantCmd command : present) {
            String name = requireText(command.name(), "型号名称不能为空").trim();
            if (!names.add(name)) {
                throw new IllegalArgumentException("型号名称不能重复：" + name);
            }
            String image = command.image() == null ? null : command.image().trim();
            if (hasText(image) && !image.startsWith("/api/files/")) {
                throw new IllegalArgumentException("型号图片必须是通过平台上传的文件");
            }
            BigDecimal variantPrice = command.price();
            if (variantPrice == null) {
                throw new IllegalArgumentException("型号「" + name + "」的价格不能为空");
            }
            ShopVariant variant = hasText(command.id())
                    ? new ShopVariant(command.id().trim(), name, variantPrice, command.stockOrDefault(), image)
                    : ShopVariant.create(name, variantPrice, command.stockOrDefault(), image);
            variants.add(variant);
        }
        return variants;
    }

    private ShopProduct requireOwnedProduct(String ownerId, String productId) {
        return productRepository.findById(productId)
                .filter(product -> product.ownerId().equals(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("商品不存在或不属于当前账号"));
    }

    private void requireNoDeliveringOrders(String productId) {
        if (orderRepository.existsDeliveringByProduct(productId)) {
            throw new IllegalArgumentException("该商品还有未完成的订单（待发货/发货中），请先处理完毕再删除");
        }
    }

    private boolean matchesKeyword(ShopProduct product, String keyword) {
        if (!hasText(keyword)) {
            return true;
        }
        String lowered = keyword.trim().toLowerCase();
        return (product.title() != null && product.title().toLowerCase().contains(lowered))
                || (product.summary() != null && product.summary().toLowerCase().contains(lowered));
    }

    private String normalizeAssetCode(String assetCode) {
        String code = requireText(assetCode, "计价货币不能为空").trim().toUpperCase();
        if (!code.matches("[A-Z0-9_]{1,16}")) {
            throw new IllegalArgumentException("货币代码只能包含字母、数字与下划线，且不超过 16 位");
        }
        return code;
    }

    private List<String> normalizeImages(List<String> images) {
        if (images == null) {
            return List.of();
        }
        List<String> normalized = images.stream()
                .filter(image -> image != null && !image.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("商品图片最多 " + MAX_IMAGES + " 张");
        }
        for (String image : normalized) {
            if (!image.startsWith("/api/files/")) {
                throw new IllegalArgumentException("商品图片必须是通过平台上传的文件");
            }
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
