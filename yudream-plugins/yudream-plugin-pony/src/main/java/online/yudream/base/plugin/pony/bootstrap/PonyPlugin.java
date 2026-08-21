package online.yudream.base.plugin.pony.bootstrap;

import online.yudream.base.plugin.pony.application.PonyAppService;
import online.yudream.base.plugin.pony.infrastructure.repository.PonyDocumentGameRepository;
import online.yudream.base.plugin.pony.infrastructure.repository.PonyDocumentPlayerRepository;
import online.yudream.base.plugin.pony.interfaces.controller.PonyAdminController;
import online.yudream.base.plugin.pony.interfaces.controller.PonyUserController;
import online.yudream.base.plugin.pony.interfaces.http.PonyHttpFacade;
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

import java.util.Base64;
import java.util.Map;

@PluginSpec(
        code = PonyPlugin.CODE,
        name = "pony",
        version = "1.0.1",
        description = "QQ 群小马归位逻辑游戏：每行每列每种颜色各 1 匹小马且互不相邻，群内协作推理放马，棋盘图片实时展示。"
)
@PluginPermissions({
        @PluginPermission(code = PonyPlugin.USE_PERMISSION, name = "参与小马归位", module = "平台插件", description = "查看自己的小马战绩"),
        @PluginPermission(code = PonyPlugin.MANAGE_PERMISSION, name = "管理小马归位", module = "平台插件", description = "查看对局记录与玩家战绩")
})
@PluginFrontend(
        moduleName = "pony",
        menuTitle = "小马归位",
        menuIcon = "i-ri:chess-line",
        menuSort = 61,
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/pony/overview",
                        name = "platform-plugin-pony-overview",
                        title = "游戏概览",
                        icon = "i-ri:dashboard-3-line",
                        component = "pony/Overview",
                        permission = PonyPlugin.MANAGE_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/pony/admin/games",
                        name = "platform-plugin-pony-games",
                        title = "对局记录",
                        icon = "i-ri:list-check-3",
                        component = "pony/Games",
                        permission = PonyPlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/pony/admin/players",
                        name = "platform-plugin-pony-players",
                        title = "玩家战绩",
                        icon = "i-ri:trophy-line",
                        component = "pony/Players",
                        permission = PonyPlugin.MANAGE_PERMISSION,
                        sort = 30
                ),
                @PluginRoute(
                        path = "/platform/plugins/pony/me/stats",
                        name = "platform-plugin-pony-my-stats",
                        title = "我的战绩",
                        icon = "i-ri:bar-chart-2-line",
                        component = "pony/MyStats",
                        permission = PonyPlugin.USE_PERMISSION,
                        sort = 40
                )
        }
)
public class PonyPlugin implements YuDreamPlugin {

    public static final String CODE = "pony";
    public static final String USE_PERMISSION = "plugin:pony:use";
    public static final String MANAGE_PERMISSION = "plugin:pony:manage";

    private PonyAppService appService;

    @Override
    public void onEnable(PluginContext context) {
        var documents = context.documents();
        this.appService = new PonyAppService(
                new PonyDocumentGameRepository(documents),
                new PonyDocumentPlayerRepository(documents),
                context.framework());
        PonyHttpFacade http = new PonyHttpFacade(appService);
        context.registerHttpController(new PonyAdminController(http));
        context.registerHttpController(new PonyUserController(http));
    }

    @PluginCommand(code = "pony.start", command = "小马", name = "开始小马归位", description = "开始一局小马归位，可选参数：棋盘尺寸 6-9（默认 8）", allowAnonymous = true)
    public void start(PluginCommandContext command, PluginContext context) {
        try {
            Integer size = null;
            for (String arg : command.arguments()) {
                if (arg.matches("\\d+")) {
                    size = Integer.parseInt(arg);
                }
            }
            replyWithBoard(command, context, appService.startGame(command.event(), command.userId(), size));
        } catch (RuntimeException e) {
            reply(command, context, "开局失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "pony.place", command = "马", name = "放置小马", description = "在指定坐标放置小马，例如 /马 4 5（列 行）", allowAnonymous = true)
    public void place(PluginCommandContext command, PluginContext context) {
        try {
            replyWithBoard(command, context, appService.placeHorse(command.event(), command.userId(),
                    intArg(command, 0), intArg(command, 1)));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "pony.mark", command = "标", name = "标记排除", description = "在指定坐标打叉或取消打叉，例如 /标 4 5（列 行）", allowAnonymous = true)
    public void mark(PluginCommandContext command, PluginContext context) {
        try {
            replyWithBoard(command, context, appService.toggleMark(command.event(), intArg(command, 0), intArg(command, 1)));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "pony.status", command = "小马状态", name = "查看对局棋盘", description = "查看本群当前对局的棋盘、生命与进度", allowAnonymous = true)
    public void status(PluginCommandContext command, PluginContext context) {
        try {
            replyWithBoard(command, context, appService.gameStatus(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "pony.end", command = "结束小马", name = "结束对局", description = "结束本群当前对局并揭晓答案", allowAnonymous = true)
    public void end(PluginCommandContext command, PluginContext context) {
        try {
            replyWithBoard(command, context, appService.endGame(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "pony.my-stats", command = "小马战绩", name = "查看个人战绩", description = "查看自己的小马归位战绩，需要先绑定系统账号")
    public void myStats(PluginCommandContext command, PluginContext context) {
        try {
            reply(command, context, appService.myStats(command.userId()));
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "pony.leaderboard", command = "小马排行", name = "查看排行榜", description = "查看小马归位胜场排行榜前十", allowAnonymous = true)
    public void leaderboard(PluginCommandContext command, PluginContext context) {
        try {
            reply(command, context, appService.leaderboard());
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "pony.help", command = "小马帮助", name = "玩法说明", description = "查看小马归位玩法与全部指令", allowAnonymous = true)
    public void help(PluginCommandContext command, PluginContext context) {
        reply(command, context, """
                🐴 小马归位玩法
                棋盘被分成若干颜色区域，要求：每行 1 匹小马、每列 1 匹小马、每种颜色 1 匹小马，且任意两匹小马不能相邻（含斜角）。
                /小马 [尺寸] — 开局（6-9，默认 8×8）
                /马 <列> <行> — 在该坐标放马，例如 /马 4 5；放对后自动把该行、列、周围一圈与同色区域标记为 ×
                /标 <列> <行> — 在该坐标打叉排除，再发一次取消
                /小马状态 — 查看当前棋盘
                /结束小马 — 投降并揭晓答案
                /小马战绩 — 查看个人战绩（需 /绑定 系统账号）
                /小马排行 — 查看胜场排行榜
                坐标看棋盘下方与左侧序号；每局 3 点生命，放错扣 1 点，耗尽则本局失败。""");
    }

    /**
     * 优先以棋盘图片回复：模板渲染成功时发送图片消息，渲染不可用或失败时降级为文本棋盘 + 原文。
     */
    private void replyWithBoard(PluginCommandContext command, PluginContext context, String text) {
        Map<String, Object> variables = appService.boardVariables(command.event(), text);
        if (variables == null) {
            reply(command, context, text);
            return;
        }
        var event = command.event();
        context.templateRenderer().render("pony-board", variables, "#pony-card").whenComplete((image, error) -> {
            if (error != null || image == null || image.content() == null || image.content().length == 0) {
                String board = appService.renderBoardText(event);
                reply(command, context, board == null ? text : board + "\n" + text);
                return;
            }
            String messageId = event.messageId();
            Map<String, Object> referrer = messageId == null || messageId.isBlank() ? Map.of() : Map.of("message_id", messageId);
            context.framework().messaging().send(new PluginMessageRequest(
                    event.connectionId(), event.platform(), event.selfId(), event.channelId(),
                    new PluginMessageContent(PluginMessageContent.Type.IMAGE,
                            "base64://" + Base64.getEncoder().encodeToString(image.content()), null, referrer)));
        });
    }

    private void reply(PluginCommandContext command, PluginContext context, String text) {
        String messageId = command.event().messageId();
        Map<String, Object> referrer = messageId == null || messageId.isBlank() ? Map.of() : Map.of("message_id", messageId);
        context.framework().messaging().send(new PluginMessageRequest(
                command.event().connectionId(), command.event().platform(), command.event().selfId(),
                command.event().channelId(),
                new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, referrer)));
    }

    private Integer intArg(PluginCommandContext command, int index) {
        if (command.arguments().size() <= index) {
            return null;
        }
        String value = command.arguments().get(index);
        if (value == null || !value.matches("\\d+")) {
            return null;
        }
        return Integer.parseInt(value);
    }

    private String safeMessage(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "未知错误" : e.getMessage();
    }
}
