package online.yudream.base.plugin.wallet.interfaces.http;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.skin.api.PluginSkinProfile;
import online.yudream.base.plugin.skin.api.PluginSkinService;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.wallet.api.PluginWalletBalance;
import online.yudream.base.plugin.wallet.api.PluginWalletTransaction;
import online.yudream.base.plugin.wallet.application.service.WalletAppService;
import online.yudream.base.plugin.wallet.application.cmd.WalletTransferCmd;
import online.yudream.base.plugin.wallet.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.wallet.interfaces.assembler.WalletWebAssembler;
import online.yudream.base.plugin.wallet.interfaces.request.WalletAssetSaveRequest;
import online.yudream.base.plugin.wallet.interfaces.request.WalletChangeRequest;
import online.yudream.base.plugin.wallet.interfaces.request.WalletGamePlayerRequest;
import online.yudream.base.plugin.wallet.interfaces.request.WalletRechargeCreateRequest;
import online.yudream.base.plugin.wallet.interfaces.request.WalletRechargeSettingsSaveRequest;
import online.yudream.base.plugin.wallet.interfaces.request.WalletTransferRequest;
import online.yudream.base.plugin.wallet.interfaces.res.WalletBalanceRes;
import online.yudream.base.plugin.wallet.interfaces.res.WalletGameBalanceRes;
import online.yudream.base.plugin.wallet.interfaces.res.WalletGameTransactionRes;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WalletHttpFacade {

    private final WalletAppService appService;
    private final FrameworkServices framework;
    private final PluginContext context;
    private final WalletWebAssembler assembler = new WalletWebAssembler();

    public WalletHttpFacade(WalletAppService appService, PluginContext context) {
        this.appService = appService;
        this.context = context;
        this.framework = context.framework();
    }

    public PluginHttpResponse status() {
        return PluginHttpResponse.ok(appService.summary());
    }

    public PluginHttpResponse assets(PluginHttpRequest request) {
        var records = appService.listAssets(page(request), size(request)).stream().map(assembler::toRes).toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", appService.assetCount()));
    }

    public PluginHttpResponse saveAsset(PluginHttpRequest request) {
        WalletAssetSaveRequest body = JsonSupport.read(request.body(), WalletAssetSaveRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.saveAsset(assembler.toCmd(body))));
    }

    public PluginHttpResponse deleteAsset(PluginHttpRequest request) {
        appService.deleteAsset(pathSegment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    public PluginHttpResponse rechargeOptions() {
        return PluginHttpResponse.ok(appService.rechargeOptions());
    }

    public PluginHttpResponse rechargeSettings() {
        return PluginHttpResponse.ok(appService.rechargeSettings());
    }

    public PluginHttpResponse saveRechargeSettings(PluginHttpRequest request) {
        WalletRechargeSettingsSaveRequest body = JsonSupport.read(request.body(), WalletRechargeSettingsSaveRequest.class);
        return PluginHttpResponse.ok(appService.saveRechargeSettings(assembler.toCmd(body)));
    }

    public PluginHttpResponse createRecharge(PluginHttpRequest request) {
        WalletRechargeCreateRequest body = JsonSupport.read(request.body(), WalletRechargeCreateRequest.class);
        Long principalUserId = request.principal().userId();
        String userId = body.userId();
        {
            if (principalUserId == null) {
                throw new IllegalArgumentException("请先登录");
            }
            String currentUserId = String.valueOf(principalUserId);
            if (body.userId() != null && !body.userId().isBlank() && !currentUserId.equals(body.userId().trim())) {
                throw new IllegalArgumentException("只能给自己的钱包充值");
            }
            userId = currentUserId;
        }
        if (userId == null || userId.isBlank()) {
            userId = principalUserId == null ? null : String.valueOf(principalUserId);
        }
        return PluginHttpResponse.ok(appService.createRecharge(assembler.toCmd(new WalletRechargeCreateRequest(
                userId,
                body.assetCode(),
                body.channelCode(),
                body.payAmount(),
                body.productType(),
                body.remark()
        ))));
    }

    private PluginHttpResponse legacyBalances(PluginHttpRequest request) {
        String userId;
        if (request.principal() == null) {
            userId = firstQuery(request, "userId");
            if (userId == null || userId.isBlank()) {
                Long principalUserId = request.principal().userId();
                userId = principalUserId == null ? null : String.valueOf(principalUserId);
            }
        } else {
            Long principalUserId = request.principal().userId();
            if (principalUserId == null) {
                throw new IllegalArgumentException("请先登录");
            }
            userId = String.valueOf(principalUserId);
        }
        return PluginHttpResponse.ok(balanceResponses(appService.balances(userId)));
    }

    public PluginHttpResponse balances(PluginHttpRequest request) {
        if (request.principal() == null || request.principal().userId() == null) {
            throw new IllegalArgumentException("Authentication required");
        }
        return PluginHttpResponse.ok(balanceResponses(appService.balances(String.valueOf(request.principal().userId()))));
    }

    public PluginHttpResponse adminBalances(PluginHttpRequest request) {
        String assetCode = firstQuery(request, "assetCode");
        var records = balanceResponses(appService.listBalances(assetCode, page(request), size(request)));
        return PluginHttpResponse.ok(Map.of("records", records, "total", appService.balanceCount(assetCode)));
    }

    public PluginHttpResponse userBalances(PluginHttpRequest request) {
        return PluginHttpResponse.ok(balanceResponses(appService.balances(pathSegment(request.path(), 2))));
    }

    public PluginHttpResponse userBalance(PluginHttpRequest request) {
        var balance = appService.balance(pathSegment(request.path(), 2), pathSegment(request.path(), 4));
        return PluginHttpResponse.ok(balanceResponse(balance, appService.historicalIncomeTotals()));
    }

    public PluginHttpResponse gameBalance(PluginHttpRequest request) {
        WalletGamePlayerRequest body = JsonSupport.read(request.body(), WalletGamePlayerRequest.class);
        GameUser user = resolveGameUser(body);
        return PluginHttpResponse.ok(gameBalanceResponse(
                appService.balance(user.userId(), body.assetCode()),
                user
        ));
    }

    public PluginHttpResponse credit(PluginHttpRequest request) {
        WalletChangeRequest body = JsonSupport.read(request.body(), WalletChangeRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.credit(assembler.toCmd(body))));
    }

    public PluginHttpResponse debit(PluginHttpRequest request) {
        WalletChangeRequest body = JsonSupport.read(request.body(), WalletChangeRequest.class);
        return PluginHttpResponse.ok(assembler.toRes(appService.debit(assembler.toCmd(body))));
    }

    public PluginHttpResponse gameCredit(PluginHttpRequest request) {
        WalletGamePlayerRequest body = JsonSupport.read(request.body(), WalletGamePlayerRequest.class);
        GameUser user = resolveGameUser(body);
        PluginWalletTransaction transaction = appService.credit(new online.yudream.base.plugin.wallet.application.cmd.WalletChangeCmd(
                user.userId(),
                body.assetCode(),
                body.amount(),
                body.businessNo(),
                body.remark()
        ));
        return PluginHttpResponse.ok(gameTransactionResponse(transaction, user));
    }

    public PluginHttpResponse gameDebit(PluginHttpRequest request) {
        WalletGamePlayerRequest body = JsonSupport.read(request.body(), WalletGamePlayerRequest.class);
        GameUser user = resolveGameUser(body);
        PluginWalletTransaction transaction = appService.debit(new online.yudream.base.plugin.wallet.application.cmd.WalletChangeCmd(
                user.userId(),
                body.assetCode(),
                body.amount(),
                body.businessNo(),
                body.remark()
        ));
        return PluginHttpResponse.ok(gameTransactionResponse(transaction, user));
    }

    public PluginHttpResponse transfer(PluginHttpRequest request) {
        WalletTransferRequest body = JsonSupport.read(request.body(), WalletTransferRequest.class);
        Long principalUserId = request.principal().userId();
        String fromUserId = body.fromUserId();
        {
            if (principalUserId == null) {
                throw new IllegalArgumentException("请先登录");
            }
            String currentUserId = String.valueOf(principalUserId);
            if (body.fromUserId() != null && !body.fromUserId().isBlank() && !currentUserId.equals(body.fromUserId().trim())) {
                throw new IllegalArgumentException("只能从自己的钱包转出");
            }
            fromUserId = currentUserId;
        }
        String toUserId = resolveUserId(firstText(body.toAccount(), body.toUserId()), "转入用户不存在");
        WalletTransferCmd cmd = new WalletTransferCmd(fromUserId, toUserId, body.assetCode(),
                body.amount(), body.businessNo(), body.remark());
        return PluginHttpResponse.ok(assembler.toRes(appService.transfer(cmd), userOf(fromUserId), userOf(toUserId)));
    }

    public PluginHttpResponse transactions(PluginHttpRequest request) {
        String userId = resolveUserIdOrNull(firstQuery(request, "user"));
        if (userId == null) {
            userId = firstQuery(request, "userId");
        }
        var records = appService.transactions(
                        firstQuery(request, "assetCode"),
                        firstQuery(request, "type"),
                        firstQuery(request, "source"),
                        userId,
                        longQuery(request, "startAt"),
                        longQuery(request, "endAt"),
                        page(request),
                        size(request)
                ).stream()
                .map(transaction -> assembler.toRes(transaction, userOf(transaction.fromUserId()), userOf(transaction.toUserId())))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", appService.transactionCount(
                firstQuery(request, "assetCode"), firstQuery(request, "type"), firstQuery(request, "source"), userId,
                longQuery(request, "startAt"), longQuery(request, "endAt"))));
    }

    public PluginHttpResponse myTransactions(PluginHttpRequest request) {
        Long principalUserId = request.principal().userId();
        if (principalUserId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        String userId = String.valueOf(principalUserId);
        var records = appService.transactions(
                        firstQuery(request, "assetCode"),
                        firstQuery(request, "type"),
                        firstQuery(request, "source"),
                        userId,
                        longQuery(request, "startAt"),
                        longQuery(request, "endAt"),
                        page(request),
                        size(request)
                ).stream()
                .map(transaction -> assembler.toRes(transaction, userOf(transaction.fromUserId()), userOf(transaction.toUserId())))
                .toList();
        return PluginHttpResponse.ok(Map.of("records", records, "total", appService.transactionCount(
                firstQuery(request, "assetCode"), firstQuery(request, "type"), firstQuery(request, "source"), userId,
                longQuery(request, "startAt"), longQuery(request, "endAt"))));
    }

    public PluginHttpResponse transactionByBusinessNo(PluginHttpRequest request) {
        String businessNo = firstQuery(request, "businessNo");
        return appService.findTransactionByBusinessNo(businessNo)
                .map(assembler::toRes)
                .map(PluginHttpResponse::ok)
                .orElseGet(() -> PluginHttpResponse.rawJson(404, Map.of("message", "流水不存在")));
    }

    private int page(PluginHttpRequest request) {
        return intQuery(request, "page", 1);
    }

    private int size(PluginHttpRequest request) {
        return intQuery(request, "size", 10);
    }

    private List<WalletBalanceRes> balanceResponses(List<PluginWalletBalance> balances) {
        Map<String, Map<String, BigDecimal>> historicalTotals = appService.historicalIncomeTotals();
        return balances.stream()
                .map(balance -> balanceResponse(balance, historicalTotals))
                .toList();
    }

    private WalletBalanceRes balanceResponse(PluginWalletBalance balance, Map<String, Map<String, BigDecimal>> historicalTotals) {
        return assembler.toRes(balance, userOf(balance.userId()), historicalTotal(historicalTotals, balance));
    }

    private WalletGameBalanceRes gameBalanceResponse(PluginWalletBalance balance, GameUser user) {
        return new WalletGameBalanceRes(
                balance.userId(),
                user.playerName(),
                user.playerUuid(),
                balance.assetCode(),
                balance.balance(),
                balance.updatedAt()
        );
    }

    private WalletGameTransactionRes gameTransactionResponse(PluginWalletTransaction transaction, GameUser user) {
        BigDecimal balanceAfter = "DEBIT".equals(transaction.type())
                ? transaction.fromBalanceAfter()
                : transaction.toBalanceAfter();
        return new WalletGameTransactionRes(
                transaction.id(),
                transaction.businessNo(),
                transaction.type(),
                transaction.source(),
                user.userId(),
                user.playerName(),
                user.playerUuid(),
                transaction.assetCode(),
                transaction.amount(),
                balanceAfter,
                transaction.remark(),
                transaction.createdAt()
        );
    }

    private BigDecimal historicalTotal(Map<String, Map<String, BigDecimal>> historicalTotals, PluginWalletBalance balance) {
        Map<String, BigDecimal> userTotals = historicalTotals.get(balance.userId());
        if (userTotals == null) {
            return BigDecimal.ZERO;
        }
        return userTotals.getOrDefault(balance.assetCode(), BigDecimal.ZERO);
    }

    private int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? defaultValue : Integer.parseInt(values.get(0));
    }

    private Long longQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : Long.parseLong(values.get(0));
    }

    private String firstQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? null : second.trim();
    }

    private String resolveUserIdOrNull(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        return resolveUser(account).map(user -> String.valueOf(user.id())).orElse(account.trim());
    }

    private String resolveUserId(String account, String message) {
        return resolveUser(account)
                .map(user -> String.valueOf(user.id()))
                .orElseThrow(() -> new IllegalArgumentException(message + "：" + account));
    }

    private Optional<PluginUserProfile> resolveUser(String account) {
        if (account == null || account.isBlank()) {
            return Optional.empty();
        }
        String value = account.trim();
        if (value.contains("@")) {
            Optional<PluginUserProfile> byEmail = framework.users().findByEmail(value);
            if (byEmail.isPresent()) {
                return byEmail;
            }
        }
        if (value.chars().allMatch(Character::isDigit)) {
            Optional<PluginUserProfile> byId = framework.users().findById(Long.parseLong(value));
            if (byId.isPresent()) {
                return byId;
            }
        }
        Optional<PluginUserProfile> byUsername = framework.users().findByUsername(value);
        if (byUsername.isPresent()) {
            return byUsername;
        }
        return value.contains("@") ? Optional.empty() : framework.users().findByEmail(value);
    }

    private GameUser resolveGameUser(WalletGamePlayerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("玩家钱包请求不能为空");
        }
        String mode = hasText(request.matchMode()) ? request.matchMode().trim().toLowerCase() : "auto";
        Optional<PluginSkinProfile> profile = Optional.empty();
        if (skinEnabled()) {
            if (!"name".equals(mode) && hasText(request.playerUuid())) {
                profile = skinProfileByUuid(request.playerUuid());
            }
            if (profile.isEmpty() && !"uuid".equals(mode) && hasText(request.playerName())) {
                profile = skinProfileByName(request.playerName());
            }
        }
        if (profile.isPresent() && hasText(profile.get().ownerId())) {
            return new GameUser(profile.get().ownerId(), profile.get().name(), profile.get().uuid());
        }
        String fallback = firstText(request.playerName(), request.playerUuid());
        String userId = resolveUserId(fallback, "玩家未绑定网站用户");
        return new GameUser(userId, request.playerName(), request.playerUuid());
    }

    private Optional<PluginSkinProfile> skinProfileByName(String playerName) {
        if (!hasText(playerName)) {
            return Optional.empty();
        }
        return skinService().flatMap(service -> service.findProfileByName(playerName.trim()));
    }

    private Optional<PluginSkinProfile> skinProfileByUuid(String playerUuid) {
        if (!hasText(playerUuid)) {
            return Optional.empty();
        }
        return skinService().flatMap(service -> service.findProfileByUuid(normalizeUuid(playerUuid)));
    }

    private Optional<PluginSkinService> skinService() {
        if (!skinEnabled()) {
            return Optional.empty();
        }
        return context.service("yudream-skin", PluginSkinService.class);
    }

    private boolean skinEnabled() {
        try {
            Class.forName("online.yudream.base.plugin.skin.api.PluginSkinService", false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private String normalizeUuid(String uuid) {
        return uuid == null ? null : uuid.trim().replace("-", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private online.yudream.base.plugin.wallet.interfaces.res.WalletUserRes userOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return framework.users().findById(Long.parseLong(userId))
                    .map(user -> new online.yudream.base.plugin.wallet.interfaces.res.WalletUserRes(
                            String.valueOf(user.id()),
                            user.username(),
                            user.nickname(),
                            user.email(),
                            user.avatar()
                    ))
                    .orElse(null);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String pathSegment(String path, int index) {
        String[] segments = trim(path).split("/");
        return index >= 0 && index < segments.length ? decode(segments[index]) : null;
    }

    private String trim(String path) {
        String value = path == null ? "" : path.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private String decode(String value) {
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record GameUser(String userId, String playerName, String playerUuid) {
    }
}
