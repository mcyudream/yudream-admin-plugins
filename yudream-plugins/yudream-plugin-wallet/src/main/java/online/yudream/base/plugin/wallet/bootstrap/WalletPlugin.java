package online.yudream.base.plugin.wallet.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginDashboardCard;
import online.yudream.base.plugin.spi.annotation.PluginCommand;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.command.PluginCommandContext;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiTool;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolCall;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolDescriptor;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolResult;
import online.yudream.base.plugin.spi.system.ai.PluginAiToolRisk;
import online.yudream.base.plugin.wallet.api.PluginWalletTransactionQuery;
import online.yudream.base.plugin.wallet.api.PluginWalletService;
import online.yudream.base.plugin.wallet.application.service.WalletAppService;
import online.yudream.base.plugin.wallet.infrastructure.repository.WalletRepository;
import online.yudream.base.plugin.wallet.interfaces.controller.WalletAdminController;
import online.yudream.base.plugin.wallet.interfaces.controller.WalletUserController;
import online.yudream.base.plugin.wallet.interfaces.controller.WalletViewController;
import online.yudream.base.plugin.wallet.interfaces.http.WalletHttpFacade;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@PluginSpec(
        code = WalletPlugin.CODE,
        name = "yudream-wallet",
        version = "1.0.0",
        description = "提供人民币余额和可扩展积分资产的钱包能力，支持余额增减、转账和流水幂等。"
)
@PluginPermissions({
        @PluginPermission(code = WalletPlugin.VIEW_PERMISSION, name = "查看钱包", module = "平台插件", description = "查看钱包资产类型和基础状态"),
        @PluginPermission(code = WalletPlugin.USER_PERMISSION, name = "使用钱包", module = "平台插件", description = "查看个人余额并发起转账"),
        @PluginPermission(code = WalletPlugin.MANAGE_PERMISSION, name = "管理钱包", module = "平台插件", description = "管理资产、余额入扣账和流水")
})
@PluginDashboardCard(
        code = "wallet-balance",
        title = "我的钱包",
        description = "展示人民币余额和积分资产概览。",
        icon = "i-ri:wallet-3-line",
        category = "钱包",
        permission = WalletPlugin.USER_PERMISSION,
        component = "yudream-wallet/DashboardBalanceCard",
        actionPath = "/platform/plugins/yudream-wallet",
        tone = "green",
        defaultW = 4,
        defaultH = 2,
        minW = 3,
        minH = 2,
        sort = 40
)
@PluginFrontend(
        moduleName = "yudreamWallet",
        menuTitle = "钱包",
        menuIcon = "i-ri:wallet-3-line",
        menuSort = 30,
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/yudream-wallet",
                        name = "platform-plugin-yudream-wallet",
                        title = "我的钱包",
                        icon = "i-ri:wallet-3-line",
                        component = "yudream-wallet/Home",
                        permission = WalletPlugin.USER_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-wallet/recharge",
                        name = "platform-plugin-yudream-wallet-recharge",
                        title = "钱包充值",
                        icon = "i-ri:bank-card-line",
                        component = "yudream-wallet/Recharge",
                        permission = WalletPlugin.USER_PERMISSION,
                        sort = 25
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-wallet/system/settings",
                        name = "platform-plugin-yudream-wallet-settings",
                        title = "币种管理",
                        icon = "i-ri:settings-3-line",
                        parentPath = "/platform/plugins/yudream-wallet/system",
                        parentTitle = "钱包管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 10,
                        component = "yudream-wallet/Settings",
                        permission = WalletPlugin.MANAGE_PERMISSION,
                        sort = 30
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-wallet/system/recharge-settings",
                        name = "platform-plugin-yudream-wallet-recharge-settings",
                        title = "充值配置",
                        icon = "i-ri:bank-card-line",
                        parentPath = "/platform/plugins/yudream-wallet/system",
                        parentTitle = "钱包管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 10,
                        component = "yudream-wallet/RechargeSettings",
                        permission = WalletPlugin.MANAGE_PERMISSION,
                        sort = 25
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-wallet/system/balances",
                        name = "platform-plugin-yudream-wallet-balances",
                        title = "余额管理",
                        icon = "i-ri:database-2-line",
                        parentPath = "/platform/plugins/yudream-wallet/system",
                        parentTitle = "钱包管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 10,
                        component = "yudream-wallet/Balances",
                        permission = WalletPlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/yudream-wallet/system/transactions",
                        name = "platform-plugin-yudream-wallet-transactions",
                        title = "系统流水",
                        icon = "i-ri:file-list-3-line",
                        parentPath = "/platform/plugins/yudream-wallet/system",
                        parentTitle = "钱包管理",
                        parentIcon = "i-ri:settings-3-line",
                        parentSort = 10,
                        component = "yudream-wallet/Transactions",
                        permission = WalletPlugin.MANAGE_PERMISSION,
                        sort = 10
                )
        }
)
public class WalletPlugin implements YuDreamPlugin {

    public static final String CODE = "yudream-wallet";
    public static final String VIEW_PERMISSION = "plugin:yudream-wallet:view";
    public static final String USER_PERMISSION = "plugin:yudream-wallet:user";
    public static final String MANAGE_PERMISSION = "plugin:yudream-wallet:manage";
    private volatile WalletAppService appService;

    @Override
    public void onEnable(PluginContext context) {
        appService = new WalletAppService(new WalletRepository(context.documents()), context);
        appService.initializeDefaults();
        context.exposeService(PluginWalletService.class, appService);
        WalletHttpFacade http = new WalletHttpFacade(appService, context);
        context.registerHttpController(new WalletViewController(http));
        context.registerHttpController(new WalletUserController(http));
        context.registerHttpController(new WalletAdminController(http));
        context.registerAiTool(new PluginAiTool() {
            @Override public PluginAiToolDescriptor descriptor() { return new PluginAiToolDescriptor("wallet.my-balance", "查询我的余额", "查询当前绑定用户的钱包余额", USER_PERMISSION, PluginAiToolRisk.READ, false, java.util.Set.of("MENTION"), Map.of()); }
            @Override public PluginAiToolResult execute(online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext execution, PluginAiToolCall call) {
                if (execution.userId() == null) return new PluginAiToolResult("denied", "当前 QQ 未绑定系统账号", Map.of());
                return new PluginAiToolResult("balance", "已查询当前用户余额", Map.of("balances", appService.balances(String.valueOf(execution.userId()))));
            }
        });
    }

    @PluginCommand(code = "wallet.balance", command = "我的余额", name = "查询钱包余额", description = "查询当前绑定账号的钱包余额")
    public void balance(PluginCommandContext command, PluginContext context) {
        if (!requireUser(command, context)) return;
        var balances = appService.balances(String.valueOf(command.userId()));
        if (balances.isEmpty()) { reply(command, context, "当前没有余额记录。"); return; }
        render(command, context, "我的余额", balances.stream().map(item -> row(item.assetCode(), item.balance().toPlainString(), "可用余额")).toList(),
                "余额以系统钱包记录为准", "我的余额：\n" + balances.stream()
                        .map(item -> "- " + item.assetCode() + "：" + item.balance()).reduce((a, b) -> a + "\n" + b).orElse(""));
    }

    @PluginCommand(code = "wallet.transactions", command = "我的流水", name = "查询钱包流水", description = "查询最近十条钱包流水")
    public void transactions(PluginCommandContext command, PluginContext context) {
        if (!requireUser(command, context)) return;
        var records = appService.transactions(new PluginWalletTransactionQuery(null, null, null, String.valueOf(command.userId()), null, null, 1, 10));
        if (records.isEmpty()) { reply(command, context, "当前没有钱包流水。"); return; }
        render(command, context, "最近流水", records.stream().map(item -> row(item.type(), item.amount().toPlainString() + " " + item.assetCode(), item.remark())).toList(),
                "最近 10 条交易", "最近流水：\n" + records.stream()
                        .map(item -> "- " + item.type() + " " + item.amount() + " " + item.assetCode() + "（" + item.remark() + "）")
                        .reduce((a, b) -> a + "\n" + b).orElse(""));
    }

    private Map<String, Object> row(String label, String value, String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label == null ? "" : label);
        row.put("value", value == null ? "" : value);
        row.put("note", note == null ? "" : note);
        return row;
    }

    private void render(PluginCommandContext command, PluginContext context, String title,
                        List<Map<String, Object>> rows, String footer, String fallback) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("title", title); variables.put("rows", rows); variables.put("footer", footer);
        context.templateRenderer().render("wallet-summary", variables, "#wallet-card").whenComplete((image, error) -> {
            if (error != null || image == null || image.content() == null || image.content().length == 0) { reply(command, context, fallback); return; }
            send(command, context, new PluginMessageContent(PluginMessageContent.Type.IMAGE,
                    "base64://" + Base64.getEncoder().encodeToString(image.content()), null, referrer(command)));
        });
    }

    private boolean requireUser(PluginCommandContext command, PluginContext context) {
        if (command.userId() != null) return true;
        reply(command, context, "当前机器人账号尚未绑定系统账号，请先完成绑定。");
        return false;
    }

    private void reply(PluginCommandContext command, PluginContext context, String text) {
        send(command, context, new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, referrer(command)));
    }

    private void send(PluginCommandContext command, PluginContext context, PluginMessageContent content) {
        if (command.event().channelId() == null || command.event().channelId().isBlank()) return;
        context.framework().messaging().send(new PluginMessageRequest(command.event().connectionId(), command.event().platform(),
                command.event().selfId(), command.event().channelId(), content));
    }

    private Map<String, Object> referrer(PluginCommandContext command) { return command.event().messageId() == null ? Map.of() : Map.of("message_id", command.event().messageId()); }
}
