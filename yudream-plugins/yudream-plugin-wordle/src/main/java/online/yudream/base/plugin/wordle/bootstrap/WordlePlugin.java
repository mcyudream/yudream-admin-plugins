package online.yudream.base.plugin.wordle.bootstrap;

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
import online.yudream.base.plugin.wordle.application.WordleAppService;
import online.yudream.base.plugin.wordle.domain.WordleMode;
import online.yudream.base.plugin.wordle.infrastructure.PinyinDictionary;
import online.yudream.base.plugin.wordle.infrastructure.WordBank;
import online.yudream.base.plugin.wordle.infrastructure.repository.WordleDocumentGameRepository;
import online.yudream.base.plugin.wordle.infrastructure.repository.WordleDocumentPlayerRepository;
import online.yudream.base.plugin.wordle.infrastructure.repository.WordleDocumentWordRepository;
import online.yudream.base.plugin.wordle.interfaces.controller.WordleAdminController;
import online.yudream.base.plugin.wordle.interfaces.controller.WordleUserController;
import online.yudream.base.plugin.wordle.interfaces.http.WordleHttpFacade;

import java.util.Base64;
import java.util.Map;

@PluginSpec(
        code = WordlePlugin.CODE,
        name = "wordle",
        version = "1.2.2",
        description = "QQ 群 Wordle 猜词游戏：群内共享对局，支持英文单词与四字成语两种模式（成语带拼音声母/韵母/声调提示）、困难模式、战绩统计与排行榜。"
)
@PluginPermissions({
        @PluginPermission(code = WordlePlugin.USE_PERMISSION, name = "参与猜词游戏", module = "平台插件", description = "查看自己的猜词战绩"),
        @PluginPermission(code = WordlePlugin.MANAGE_PERMISSION, name = "管理猜词游戏", module = "平台插件", description = "维护词库、查看对局记录与玩家战绩")
})
@PluginFrontend(
        moduleName = "wordle",
        menuTitle = "猜词游戏",
        menuIcon = "i-ri:gamepad-line",
        menuSort = 60,
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/wordle/overview",
                        name = "platform-plugin-wordle-overview",
                        title = "游戏概览",
                        icon = "i-ri:dashboard-3-line",
                        component = "wordle/Overview",
                        permission = WordlePlugin.MANAGE_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/wordle/admin/words",
                        name = "platform-plugin-wordle-words",
                        title = "词库管理",
                        icon = "i-ri:book-2-line",
                        component = "wordle/Words",
                        permission = WordlePlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/wordle/admin/games",
                        name = "platform-plugin-wordle-games",
                        title = "对局记录",
                        icon = "i-ri:list-check-3",
                        component = "wordle/Games",
                        permission = WordlePlugin.MANAGE_PERMISSION,
                        sort = 30
                ),
                @PluginRoute(
                        path = "/platform/plugins/wordle/admin/players",
                        name = "platform-plugin-wordle-players",
                        title = "玩家战绩",
                        icon = "i-ri:trophy-line",
                        component = "wordle/Players",
                        permission = WordlePlugin.MANAGE_PERMISSION,
                        sort = 40
                ),
                @PluginRoute(
                        path = "/platform/plugins/wordle/me/stats",
                        name = "platform-plugin-wordle-my-stats",
                        title = "我的战绩",
                        icon = "i-ri:bar-chart-2-line",
                        component = "wordle/MyStats",
                        permission = WordlePlugin.USE_PERMISSION,
                        sort = 50
                )
        }
)
public class WordlePlugin implements YuDreamPlugin {

    public static final String CODE = "wordle";
    public static final String USE_PERMISSION = "plugin:wordle:use";
    public static final String MANAGE_PERMISSION = "plugin:wordle:manage";

    private WordleAppService appService;

    @Override
    public void onEnable(PluginContext context) {
        var documents = context.documents();
        var words = new WordleDocumentWordRepository(documents);
        this.appService = new WordleAppService(
                new WordleDocumentGameRepository(documents),
                new WordleDocumentPlayerRepository(documents),
                words,
                new WordBank(words),
                new PinyinDictionary(),
                context.framework());
        WordleHttpFacade http = new WordleHttpFacade(appService);
        context.registerHttpController(new WordleAdminController(http));
        context.registerHttpController(new WordleUserController(http));
    }

    @PluginCommand(code = "wordle.start", command = "猜单词", name = "开始英文猜词", description = "开始一局英文单词猜词，可选参数：长度（4/5/6）与「困难」", allowAnonymous = true)
    public void startEnglish(PluginCommandContext command, PluginContext context) {
        try {
            Integer length = null;
            boolean hard = false;
            for (String arg : command.arguments()) {
                if (arg.matches("\\d+")) {
                    length = Integer.parseInt(arg);
                } else if (arg.equals("困难") || arg.equalsIgnoreCase("hard")) {
                    hard = true;
                }
            }
            reply(command, context, appService.startGame(command.event(), command.userId(), WordleMode.ENGLISH, length, hard));
        } catch (RuntimeException e) {
            reply(command, context, "开局失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "wordle.start-idiom", command = "猜成语", name = "开始成语猜词", description = "开始一局四字成语猜词，可加参数「困难」", allowAnonymous = true)
    public void startIdiom(PluginCommandContext command, PluginContext context) {
        try {
            boolean hard = command.arguments().stream().anyMatch(arg -> arg.equals("困难") || arg.equalsIgnoreCase("hard"));
            reply(command, context, appService.startGame(command.event(), command.userId(), WordleMode.IDIOM, 4, hard));
        } catch (RuntimeException e) {
            reply(command, context, "开局失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "wordle.guess", command = "猜", name = "猜一个词", description = "提交一次猜测，例如 /猜 apple；/猜 随机 由词库随机代猜", allowAnonymous = true)
    public void guess(PluginCommandContext command, PluginContext context) {
        try {
            if (command.arguments().isEmpty()) {
                reply(command, context, "用法：/猜 <单词或成语>，例如 /猜 apple；/猜 随机 由词库随机代猜。");
                return;
            }
            replyWithBoard(command, context, appService.guess(command.event(), command.userId(), command.arguments().getFirst()));
        } catch (RuntimeException e) {
            reply(command, context, "猜测失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "wordle.end", command = "结束猜词", name = "结束对局", description = "结束本群当前对局并揭晓答案", allowAnonymous = true)
    public void end(PluginCommandContext command, PluginContext context) {
        try {
            replyWithBoard(command, context, appService.endGame(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "wordle.status", command = "猜词状态", name = "查看对局进度", description = "查看本群当前对局的模式、进度与全部历史猜测", allowAnonymous = true)
    public void status(PluginCommandContext command, PluginContext context) {
        try {
            replyWithBoard(command, context, appService.gameStatus(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "wordle.my-stats", command = "猜词战绩", name = "查看个人战绩", description = "查看自己的猜词战绩，需要先绑定系统账号")
    public void myStats(PluginCommandContext command, PluginContext context) {
        try {
            reply(command, context, appService.myStats(command.userId()));
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "wordle.leaderboard", command = "猜词排行", name = "查看排行榜", description = "查看猜词总胜场排行榜前十", allowAnonymous = true)
    public void leaderboard(PluginCommandContext command, PluginContext context) {
        try {
            reply(command, context, appService.leaderboard());
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "wordle.help", command = "猜词帮助", name = "玩法说明", description = "查看猜词游戏玩法与全部指令", allowAnonymous = true)
    public void help(PluginCommandContext command, PluginContext context) {
        reply(command, context, """
                🎮 猜词游戏玩法
                /猜单词 [长度] [困难] — 开始英文猜词（默认 5 字母，内置 4/5/6 词库）
                /猜成语 [困难] — 开始四字成语猜词
                /猜 <词> — 提交猜测；/猜 随机 由词库随机代猜
                /猜词状态 — 查看当前对局进度与历史猜测
                /结束猜词 — 投降并揭晓答案
                /猜词战绩 — 查看个人战绩（需 /绑定 系统账号）
                /猜词排行 — 查看总胜场排行榜
                🟩 字母与位置都正确 🟨 含有该字母但位置不对 ⬜ 答案中不存在
                成语模式额外标注拼音：每个字以标准带调拼音展示，声母、韵母、声调按命中状态分别着色（🟩 位置正确 🟨 答案中存在 ⬜ 不存在）；文本 🔤 行的色块顺序同样为声母（零声母省略）、韵母、声调。
                困难模式：必须沿用已猜出的绿色位置与黄色字母。""");
    }

    private void reply(PluginCommandContext command, PluginContext context, String text) {
        String messageId = command.event().messageId();
        Map<String, Object> referrer = messageId == null || messageId.isBlank() ? Map.of() : Map.of("message_id", messageId);
        context.framework().messaging().send(new PluginMessageRequest(
                command.event().connectionId(), command.event().platform(), command.event().selfId(),
                command.event().channelId(),
                new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, referrer)));
    }

    /**
     * 优先以棋盘图片回复：模板渲染成功时发送图片消息，渲染不可用或失败时降级为纯文本。
     */
    private void replyWithBoard(PluginCommandContext command, PluginContext context, String text) {
        Map<String, Object> variables = appService.boardVariables(command.event(), text);
        if (variables == null) {
            reply(command, context, text);
            return;
        }
        var event = command.event();
        context.templateRenderer().render("wordle-board", variables, "#wordle-card").whenComplete((image, error) -> {
            if (error != null || image == null || image.content() == null || image.content().length == 0) {
                reply(command, context, text);
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

    private String safeMessage(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "未知错误" : e.getMessage();
    }
}
