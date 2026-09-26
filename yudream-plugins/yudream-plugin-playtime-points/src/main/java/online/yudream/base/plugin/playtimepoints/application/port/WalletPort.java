package online.yudream.base.plugin.playtimepoints.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** 用户钱包插件端口：货币类型查询与入账。实现集中在 infrastructure，隔离 provider API 类型。 */
public interface WalletPort {

    boolean walletAvailable();

    List<AssetRef> assets();

    Optional<AssetRef> findAsset(String assetCode);

    /** 当前余额（十进制字符串）；钱包不可用或查询失败时为空。 */
    Optional<String> balance(String userId, String assetCode);

    /** 入账。amount 必须满足钱包资产精度；重复 businessNo 由钱包幂等。 */
    void credit(String userId, String assetCode, BigDecimal amount, String businessNo, String remark);

    record AssetRef(String code, String name, String symbol, int scale, boolean money, boolean enabled) {
    }
}
