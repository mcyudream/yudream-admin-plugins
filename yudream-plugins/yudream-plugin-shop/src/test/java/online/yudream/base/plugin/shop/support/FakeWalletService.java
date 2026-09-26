package online.yudream.base.plugin.shop.support;

import online.yudream.base.plugin.wallet.api.PluginWalletAsset;
import online.yudream.base.plugin.wallet.api.PluginWalletBalance;
import online.yudream.base.plugin.wallet.api.PluginWalletChangeRequest;
import online.yudream.base.plugin.wallet.api.PluginWalletService;
import online.yudream.base.plugin.wallet.api.PluginWalletTransaction;
import online.yudream.base.plugin.wallet.api.PluginWalletTransferRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试用的钱包：复刻真实实现对业务单号的幂等语义（同一 businessNo 只动一次账并返回首笔流水），
 * 支持转账、扣减与入账，并允许制造扣减/入账失败。
 */
public class FakeWalletService implements PluginWalletService {

    private final Map<String, PluginWalletAsset> assets = new LinkedHashMap<>();
    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Map<String, PluginWalletTransaction> applied = new LinkedHashMap<>();
    private final List<String> debits = new ArrayList<>();
    private final List<String> credits = new ArrayList<>();
    private final List<String> transfers = new ArrayList<>();
    private boolean failDebits;
    private boolean failCredits;
    private boolean failTransfers;

    public FakeWalletService() {
        ensureAsset(new PluginWalletAsset("POINT", "积分", "积分", 0, false, true, false, BigDecimal.ONE));
        ensureAsset(new PluginWalletAsset("CNY", "人民币", "元", 2, true, true, true, new BigDecimal("0.01")));
    }

    public FakeWalletService setBalance(String userId, String assetCode, String value) {
        balances.put(key(userId, assetCode), new BigDecimal(value));
        return this;
    }

    public BigDecimal balanceOf(String userId, String assetCode) {
        return balances.getOrDefault(key(userId, assetCode), BigDecimal.ZERO);
    }

    public List<String> debitBusinessNos() {
        return List.copyOf(debits);
    }

    public List<String> creditBusinessNos() {
        return List.copyOf(credits);
    }

    public List<String> transferBusinessNos() {
        return List.copyOf(transfers);
    }

    /** 让接下来的扣减（积分兑换付款）直接抛错。 */
    public void failDebits() {
        this.failDebits = true;
    }

    /** 让接下来的入账（取消/失败退款）直接抛错。 */
    public void failCredits() {
        this.failCredits = true;
    }

    @Override
    public List<PluginWalletAsset> assets() {
        return List.copyOf(assets.values());
    }

    @Override
    public Optional<PluginWalletAsset> findAsset(String assetCode) {
        return Optional.ofNullable(assets.get(assetCode));
    }

    @Override
    public PluginWalletAsset ensureAsset(PluginWalletAsset asset) {
        assets.put(asset.code(), asset);
        return asset;
    }

    @Override
    public List<PluginWalletBalance> balances(String userId) {
        return balances.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(userId + "|"))
                .map(entry -> new PluginWalletBalance(userId, entry.getKey().substring(entry.getKey().indexOf('|') + 1),
                        entry.getValue(), System.currentTimeMillis()))
                .toList();
    }

    @Override
    public PluginWalletBalance balance(String userId, String assetCode) {
        return new PluginWalletBalance(userId, assetCode, balanceOf(userId, assetCode), System.currentTimeMillis());
    }

    @Override
    public PluginWalletTransaction credit(PluginWalletChangeRequest request) {
        PluginWalletTransaction existing = existing(request.businessNo());
        if (existing != null) {
            return existing;
        }
        if (failCredits) {
            throw new IllegalStateException("钱包暂时不可用");
        }
        BigDecimal after = balanceOf(request.userId(), request.assetCode()).add(request.amount());
        balances.put(key(request.userId(), request.assetCode()), after);
        return applyChange(request, "CREDIT", request.userId(), request.userId(), after, after, credits);
    }

    @Override
    public PluginWalletTransaction debit(PluginWalletChangeRequest request) {
        PluginWalletTransaction existing = existing(request.businessNo());
        if (existing != null) {
            return existing;
        }
        if (failDebits) {
            throw new IllegalStateException("钱包暂时不可用");
        }
        BigDecimal after = balanceOf(request.userId(), request.assetCode()).subtract(request.amount());
        if (after.signum() < 0) {
            throw new IllegalArgumentException("余额不足");
        }
        balances.put(key(request.userId(), request.assetCode()), after);
        return applyChange(request, "DEBIT", request.userId(), request.userId(), after, after, debits);
    }

    @Override
    public PluginWalletTransaction transfer(PluginWalletTransferRequest request) {
        PluginWalletTransaction existing = existing(request.businessNo());
        if (existing != null) {
            return existing;
        }
        if (failTransfers) {
            throw new IllegalStateException("钱包暂时不可用");
        }
        BigDecimal fromAfter = balanceOf(request.fromUserId(), request.assetCode()).subtract(request.amount());
        if (fromAfter.signum() < 0) {
            throw new IllegalArgumentException("余额不足");
        }
        BigDecimal toAfter = balanceOf(request.toUserId(), request.assetCode()).add(request.amount());
        balances.put(key(request.fromUserId(), request.assetCode()), fromAfter);
        balances.put(key(request.toUserId(), request.assetCode()), toAfter);
        return applyChange(new PluginWalletChangeRequest(request.fromUserId(), request.assetCode(),
                        request.amount(), request.businessNo(), request.remark()),
                "TRANSFER", request.fromUserId(), request.toUserId(), fromAfter, toAfter, transfers);
    }

    @Override
    public Optional<PluginWalletTransaction> findTransactionByBusinessNo(String businessNo) {
        return Optional.ofNullable(businessNo == null ? null : applied.get(businessNo.trim()));
    }

    private PluginWalletTransaction existing(String businessNo) {
        return businessNo == null ? null : applied.get(businessNo.trim());
    }

    private PluginWalletTransaction applyChange(PluginWalletChangeRequest request, String type, String fromUserId,
                                                String toUserId, BigDecimal fromAfter, BigDecimal toAfter,
                                                List<String> log) {
        PluginWalletTransaction transaction = new PluginWalletTransaction("tx-" + request.businessNo(),
                request.businessNo(), type, "shop-test", request.assetCode(), fromUserId, toUserId,
                request.amount(), fromAfter, toAfter, request.remark(), System.currentTimeMillis());
        if (request.businessNo() != null) {
            applied.put(request.businessNo().trim(), transaction);
        }
        log.add(String.valueOf(request.businessNo()));
        return transaction;
    }

    private static String key(String userId, String assetCode) {
        return userId + "|" + assetCode;
    }
}
