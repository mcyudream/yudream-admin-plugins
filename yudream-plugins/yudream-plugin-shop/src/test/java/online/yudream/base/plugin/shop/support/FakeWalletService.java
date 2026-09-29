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
 * 支持转账、扣减与入账，并允许制造扣减/入账/转账失败或按业务单号前缀失败（用于手续费两腿编排的失败注入）。
 */
public class FakeWalletService implements PluginWalletService {

    /** 一次账务变更的完整快照，便于断言「哪一腿、多少钱、从谁到谁」。 */
    public record Movement(String type, String businessNo, String fromUserId, String toUserId,
                           String assetCode, BigDecimal amount) {
    }

    private final Map<String, PluginWalletAsset> assets = new LinkedHashMap<>();
    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Map<String, PluginWalletTransaction> applied = new LinkedHashMap<>();
    private final List<String> debits = new ArrayList<>();
    private final List<String> credits = new ArrayList<>();
    private final List<String> transfers = new ArrayList<>();
    private final List<Movement> movements = new ArrayList<>();
    private final List<String> failingBusinessNoPrefixes = new ArrayList<>();
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

    /** 让接下来的转账（买家付款、卖家退款、手续费转账）直接抛错。 */
    public void failTransfers() {
        this.failTransfers = true;
    }

    /**
     * 指定业务单号前缀的账务操作抛错，用于精确制造「一腿成功、另一腿失败」：
     * 例如 {@code failBusinessNoStartingWith("shop:fee:")} 只让手续费腿失败，
     * {@code "shop:net-revert:"} 让卖家货款腿的冲正失败。
     */
    public FakeWalletService failBusinessNoStartingWith(String prefix) {
        failingBusinessNoPrefixes.add(prefix);
        return this;
    }

    /** 清空全部失败开关（含前缀失败），回到健康钱包。 */
    public FakeWalletService recover() {
        failDebits = false;
        failCredits = false;
        failTransfers = false;
        failingBusinessNoPrefixes.clear();
        return this;
    }

    /** 全部账务变更（按发生顺序），用于断言两腿金额与对冲单号。 */
    public List<Movement> movements() {
        return List.copyOf(movements);
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
        requireNoInjectedFailure(request.businessNo());
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
        requireNoInjectedFailure(request.businessNo());
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
        requireNoInjectedFailure(request.businessNo());
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

    /** 注入的按单号前缀失败：命中即抛错（金额与账户都不动）。 */
    private void requireNoInjectedFailure(String businessNo) {
        if (businessNo == null) {
            return;
        }
        String trimmed = businessNo.trim();
        for (String prefix : failingBusinessNoPrefixes) {
            if (trimmed.startsWith(prefix)) {
                throw new IllegalStateException("钱包暂时不可用（注入失败：" + prefix + "）");
            }
        }
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
        movements.add(new Movement(type, request.businessNo(), fromUserId, toUserId, request.assetCode(),
                request.amount()));
        log.add(String.valueOf(request.businessNo()));
        return transaction;
    }

    private static String key(String userId, String assetCode) {
        return userId + "|" + assetCode;
    }
}
