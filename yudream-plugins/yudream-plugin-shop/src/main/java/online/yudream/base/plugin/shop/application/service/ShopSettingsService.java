package online.yudream.base.plugin.shop.application.service;

import online.yudream.base.plugin.shop.domain.valobj.ShopSettings;
import online.yudream.base.plugin.shop.infrastructure.repository.ShopSettingsRepository;
import online.yudream.base.plugin.shop.infrastructure.wallet.ShopWalletPort;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 商店发布设置用例：读取/保存设置，并评估普通用户的上架资格。
 * 管理端治理动作不走资格评估；资格仅约束用户的发布、编辑与重新上架。
 */
public class ShopSettingsService {

    private final ShopSettingsRepository settingsRepository;
    private final ShopWalletPort walletPort;

    public ShopSettingsService(ShopSettingsRepository settingsRepository, ShopWalletPort walletPort) {
        this.settingsRepository = settingsRepository;
        this.walletPort = walletPort;
    }

    public ShopSettings current() {
        return settingsRepository.find().normalized();
    }

    public ShopSettings save(ShopSettings settings) {
        ShopSettings normalized = settings.normalized();
        if (walletPort.available()) {
            if (normalized.requiresBalance() && !walletPort.assetEnabled(normalized.publishAssetCode())) {
                throw new IllegalArgumentException("积分货币不存在或已停用：" + normalized.publishAssetCode());
            }
            for (String code : normalized.allowedAssetCodes()) {
                if (!walletPort.assetEnabled(code)) {
                    throw new IllegalArgumentException("交易币种不存在或已停用：" + code);
                }
            }
        }
        return settingsRepository.save(normalized);
    }

    /** 评估用户上架资格；allowed=false 时 reason 携带可直接展示的原因。 */
    public PublishQualification qualify(String userId) {
        ShopSettings settings = current();
        boolean walletAvailable = walletPort.available();
        if (!settings.allowUserPublish()) {
            return denied(settings, "管理员已关闭用户上架功能，暂时无法发布或上架商品", walletAvailable);
        }
        if (!settings.requiresBalance()) {
            return new PublishQualification(true, null, settings.publishAssetCode(),
                    settings.publishMinBalance(), null, walletAvailable);
        }
        if (!walletAvailable) {
            return denied(settings, "钱包插件不可用，无法校验上架积分门槛", false);
        }
        Optional<BigDecimal> balance = walletPort.balance(userId, settings.publishAssetCode());
        if (balance.isEmpty()) {
            return denied(settings, "暂时无法查询钱包余额，请稍后重试", true);
        }
        if (balance.get().compareTo(settings.publishMinBalance()) < 0) {
            return denied(settings, String.format("上架商品需要至少 %s %s，当前余额 %s",
                    settings.publishMinBalance().stripTrailingZeros().toPlainString(),
                    settings.publishAssetCode(),
                    balance.get().stripTrailingZeros().toPlainString()), true);
        }
        return new PublishQualification(true, null, settings.publishAssetCode(), settings.publishMinBalance(),
                balance.get(), true);
    }

    private PublishQualification denied(ShopSettings settings, String reason, boolean walletAvailable) {
        return new PublishQualification(false, reason, settings.publishAssetCode(),
                settings.publishMinBalance(), null, walletAvailable);
    }

    /** 上架资格视图：balance 仅在配置门槛且查询成功时返回。 */
    public record PublishQualification(boolean allowed, String reason, String publishAssetCode,
                                       BigDecimal publishMinBalance, BigDecimal balance, boolean walletAvailable) {
    }
}
