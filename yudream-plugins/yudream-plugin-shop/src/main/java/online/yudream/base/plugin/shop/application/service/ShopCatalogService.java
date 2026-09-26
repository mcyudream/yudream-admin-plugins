package online.yudream.base.plugin.shop.application.service;

import online.yudream.base.plugin.shop.api.PluginShopProduct;
import online.yudream.base.plugin.shop.application.cmd.ShopProductSaveCmd;
import online.yudream.base.plugin.shop.domain.aggregate.ShopProduct;
import online.yudream.base.plugin.shop.domain.enumerate.ShopProductStatus;
import online.yudream.base.plugin.shop.domain.valobj.ShopSettings;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopOrderRepository;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopProductRepository;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    public ShopPage<ShopProduct> pagePlaza(String keyword, String assetCode, int page, int size) {
        List<ShopProduct> filtered = productRepository.findAll().stream()
                .filter(ShopProduct::onShelf)
                .filter(product -> !hasText(assetCode) || assetCode.trim().equalsIgnoreCase(product.assetCode()))
                .filter(product -> matchesKeyword(product, keyword))
                .sorted(Comparator.comparingLong(ShopProduct::createdAt).reversed())
                .toList();
        return ShopPage.of(filtered, page, size);
    }

    public ShopProduct plazaDetail(String productId) {
        return productRepository.findById(productId)
                .filter(ShopProduct::onShelf)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在或已下架"));
    }

    /** 广场货币筛选项：上架商品实际用到的货币，有钱包时附带名称与符号。 */
    public List<ShopWalletPort.WalletAsset> plazaCurrencies() {
        Set<String> codes = new LinkedHashSet<>();
        productRepository.findAll().stream()
                .filter(ShopProduct::onShelf)
                .forEach(product -> codes.add(product.assetCode()));
        Map<String, ShopWalletPort.WalletAsset> walletAssets = new LinkedHashMap<>();
        walletPort.enabledAssets().forEach(asset -> walletAssets.put(asset.code(), asset));
        return codes.stream()
                .map(code -> walletAssets.getOrDefault(code, new ShopWalletPort.WalletAsset(code, code, "¥", 2)))
                .toList();
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

    public ShopProduct saveMyProduct(String ownerId, ShopProductSaveCmd cmd) {
        boolean creating = cmd.id() == null || cmd.id().isBlank();
        requireUserPublishAllowed(ownerId, creating);
        return doSave(ownerId, cmd);
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
                .sorted(Comparator.comparingLong(ShopProduct::updatedAt).reversed())
                .toList();
        return ShopPage.of(filtered, page, size);
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
     * 管理员代上架/编辑商品：不走用户上架开关与积分门槛（管理能力），
     * targetOwnerId 为空时归属管理员自己。
     */
    public ShopProduct adminSaveProduct(String operatorId, String targetOwnerId, ShopProductSaveCmd cmd) {
        String ownerId = targetOwnerId == null || targetOwnerId.isBlank() ? operatorId : targetOwnerId.trim();
        return doSave(ownerId, cmd);
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

    private ShopProduct doSave(String ownerId, ShopProductSaveCmd cmd) {
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
        BigDecimal price = cmd.price();
        if (cmd.id() == null || cmd.id().isBlank()) {
            return productRepository.save(ShopProduct.create(ownerId, cmd.title(), summary, description,
                    images, assetCode, price, stock, type, typeConfig));
        }
        ShopProduct existing = requireOwnedProduct(ownerId, cmd.id());
        return productRepository.save(existing.update(cmd.title(), summary, description, images, assetCode,
                price, stock, type, typeConfig));
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
