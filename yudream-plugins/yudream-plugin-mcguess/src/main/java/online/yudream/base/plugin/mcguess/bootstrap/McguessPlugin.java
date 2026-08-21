package online.yudream.base.plugin.mcguess.bootstrap;

import online.yudream.base.plugin.mcguess.application.BingoAppService;
import online.yudream.base.plugin.mcguess.application.CollectionService;
import online.yudream.base.plugin.mcguess.application.FogAppService;
import online.yudream.base.plugin.mcguess.application.HolAppService;
import online.yudream.base.plugin.mcguess.application.McguessAppService;
import online.yudream.base.plugin.mcguess.application.McguessStatsService;
import online.yudream.base.plugin.mcguess.application.McguessSupport;
import online.yudream.base.plugin.mcguess.application.MobAppService;
import online.yudream.base.plugin.mcguess.application.QuizAppService;
import online.yudream.base.plugin.mcguess.application.RecipeAppService;
import online.yudream.base.plugin.mcguess.application.SpotAppService;
import online.yudream.base.plugin.mcguess.domain.BingoGameRepository;
import online.yudream.base.plugin.mcguess.domain.FogGameRepository;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McMobCatalog;
import online.yudream.base.plugin.mcguess.domain.McguessGameRepository;
import online.yudream.base.plugin.mcguess.domain.McguessPlayerRepository;
import online.yudream.base.plugin.mcguess.domain.MobGameRepository;
import online.yudream.base.plugin.mcguess.domain.QuizGameRepository;
import online.yudream.base.plugin.mcguess.domain.RecipeGameRepository;
import online.yudream.base.plugin.mcguess.domain.SpotGameRepository;
import online.yudream.base.plugin.mcguess.infrastructure.IconSupport;
import online.yudream.base.plugin.mcguess.infrastructure.McDataLoader;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentBingoGameRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentFogGameRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentGameRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentMobGameRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentPlayerRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentQuizGameRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentRecipeGameRepository;
import online.yudream.base.plugin.mcguess.infrastructure.repository.McguessDocumentSpotGameRepository;
import online.yudream.base.plugin.mcguess.interfaces.controller.McguessAdminController;
import online.yudream.base.plugin.mcguess.interfaces.controller.McguessUserController;
import online.yudream.base.plugin.mcguess.interfaces.http.McguessHttpFacade;
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
        code = McguessPlugin.CODE,
        name = "mcguess",
        version = "2.1.0",
        description = "QQ 群 MC 猜谜（JE 1.20.5）：猜物（配方树推理）、猜生物（条件填格子）、猜合成（反向填配方）、"
                + "迷雾（图标渐显）、快答（合成计数抢答）、宾果（5x5 连线）、找茬（配方找错格）、比大小（出现次数连胜）"
                + "与物品图鉴收集；群回合制共享进度、结束后可立即再开新局，支持智能匹配、提示、战绩排行与图片棋盘。"
)
@PluginPermissions({
        @PluginPermission(code = McguessPlugin.USE_PERMISSION, name = "参与猜谜游戏", module = "平台插件", description = "查看自己的猜谜战绩"),
        @PluginPermission(code = McguessPlugin.MANAGE_PERMISSION, name = "管理猜谜游戏", module = "平台插件", description = "查看各模式的对局记录与玩家战绩")
})
@PluginFrontend(
        moduleName = "mcguess",
        menuTitle = "MC 猜谜",
        menuIcon = "i-ri:treasure-map-line",
        menuSort = 61,
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/mcguess/overview",
                        name = "platform-plugin-mcguess-overview",
                        title = "游戏概览",
                        icon = "i-ri:dashboard-3-line",
                        component = "mcguess/Overview",
                        permission = McguessPlugin.MANAGE_PERMISSION,
                        sort = 10
                ),
                @PluginRoute(
                        path = "/platform/plugins/mcguess/admin/games",
                        name = "platform-plugin-mcguess-games",
                        title = "对局记录",
                        icon = "i-ri:list-check-3",
                        component = "mcguess/Games",
                        permission = McguessPlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/mcguess/admin/players",
                        name = "platform-plugin-mcguess-players",
                        title = "玩家战绩",
                        icon = "i-ri:trophy-line",
                        component = "mcguess/Players",
                        permission = McguessPlugin.MANAGE_PERMISSION,
                        sort = 30
                ),
                @PluginRoute(
                        path = "/platform/plugins/mcguess/me/stats",
                        name = "platform-plugin-mcguess-my-stats",
                        title = "我的战绩",
                        icon = "i-ri:bar-chart-2-line",
                        component = "mcguess/MyStats",
                        permission = McguessPlugin.USE_PERMISSION,
                        sort = 40
                )
        }
)
public class McguessPlugin implements YuDreamPlugin {

    public static final String CODE = "mcguess";
    public static final String USE_PERMISSION = "plugin:mcguess:use";
    public static final String MANAGE_PERMISSION = "plugin:mcguess:manage";

    private McguessAppService itemService;
    private MobAppService mobService;
    private RecipeAppService recipeService;
    private FogAppService fogService;
    private HolAppService holService;
    private QuizAppService quizService;
    private BingoAppService bingoService;
    private SpotAppService spotService;
    private CollectionService collectionService;
    private McguessStatsService statsService;

    @Override
    public void onEnable(PluginContext context) {
        ClassLoader classLoader = getClass().getClassLoader();
        var documents = context.documents();
        McCatalog catalog = McDataLoader.load(classLoader);
        McMobCatalog mobCatalog = McDataLoader.loadMobs(classLoader);
        IconSupport icons = new IconSupport(classLoader);
        McguessGameRepository itemGames = new McguessDocumentGameRepository(documents);
        MobGameRepository mobGames = new McguessDocumentMobGameRepository(documents);
        RecipeGameRepository recipeGames = new McguessDocumentRecipeGameRepository(documents);
        FogGameRepository fogGames = new McguessDocumentFogGameRepository(documents);
        QuizGameRepository quizGames = new McguessDocumentQuizGameRepository(documents);
        BingoGameRepository bingoGames = new McguessDocumentBingoGameRepository(documents);
        SpotGameRepository spotGames = new McguessDocumentSpotGameRepository(documents);
        McguessPlayerRepository players = new McguessDocumentPlayerRepository(documents);
        McguessSupport support = new McguessSupport(players, context.framework());
        this.itemService = new McguessAppService(itemGames, catalog, icons, support);
        this.mobService = new MobAppService(mobGames, mobCatalog, icons, support);
        this.recipeService = new RecipeAppService(recipeGames, catalog, icons, support);
        this.fogService = new FogAppService(fogGames, catalog, icons, support);
        this.holService = new HolAppService(catalog, icons, support);
        this.quizService = new QuizAppService(quizGames, catalog, icons, support);
        this.bingoService = new BingoAppService(bingoGames, catalog, icons, support);
        this.spotService = new SpotAppService(spotGames, catalog, icons, support);
        this.collectionService = new CollectionService(players, catalog, icons);
        this.statsService = new McguessStatsService(itemGames, mobGames, recipeGames,
                fogGames, quizGames, bingoGames, spotGames, players, catalog, mobCatalog);
        McguessHttpFacade http = new McguessHttpFacade(statsService);
        context.registerHttpController(new McguessAdminController(http));
        context.registerHttpController(new McguessUserController(http));
    }

    // ---------------------------------------------------------------- 猜物（配方树推理）

    @PluginCommand(code = "mcguess.guess", command = "猜物", name = "猜一个物品", description = "猜测目标物品，例如 /猜物 钻石剑；不带参数时查看本局棋盘", allowAnonymous = true)
    public void guess(PluginCommandContext command, PluginContext context) {
        try {
            if (command.arguments().isEmpty()) {
                replyWithItemBoard(command, context, itemService.status(command.event()));
                return;
            }
            String input = String.join(" ", command.arguments());
            replyWithItemBoard(command, context, itemService.guess(command.event(), command.userId(), input));
        } catch (RuntimeException e) {
            reply(command, context, "猜测失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.cell", command = "猜物格子", name = "查看格子配方", description = "查看目标配方第 N 格（1-9）中物品的合成配方，例如 /猜物格子 5", allowAnonymous = true)
    public void cell(PluginCommandContext command, PluginContext context) {
        try {
            if (command.arguments().isEmpty()) {
                reply(command, context, "用法：/猜物格子 <1-9>，查看目标配方对应格子中物品的合成配方（需先揭示该格）。");
                return;
            }
            int index;
            try {
                index = Integer.parseInt(command.arguments().getFirst().trim());
            } catch (NumberFormatException e) {
                reply(command, context, "格子编号需要在 1-9 之间（3x3 配方从左到右、从上到下）。");
                return;
            }
            McguessAppService.CellRecipeView view = itemService.cellRecipe(command.event(), index);
            if (view.variables() == null) {
                reply(command, context, view.text());
                return;
            }
            renderImage(command, context, "mcguess-recipe", view.variables(), "#mcguess-recipe-card", view.text());
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.hint", command = "猜物提示", name = "使用猜物提示", description = "连续空猜 6 次后可用，随机揭示目标配方中的一格", allowAnonymous = true)
    public void hint(PluginCommandContext command, PluginContext context) {
        try {
            replyWithItemBoard(command, context, itemService.hint(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.surrender", command = "结束猜物", name = "结束本局猜物", description = "投降并揭晓本局目标物品，结束后可立即开始新一局", allowAnonymous = true)
    public void surrender(PluginCommandContext command, PluginContext context) {
        try {
            replyWithItemBoard(command, context, itemService.surrender(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    // ---------------------------------------------------------------- 猜生物（条件填格子）

    @PluginCommand(code = "mcguess.mob.fill", command = "猜生物", name = "填一个生物", description = "把生物填入满足行、列条件的格子，例如 /猜生物 5 僵尸；不带参数时查看本局棋盘", allowAnonymous = true)
    public void mobFill(PluginCommandContext command, PluginContext context) {
        try {
            if (command.arguments().isEmpty()) {
                replyWithMobBoard(command, context, mobService.status(command.event()));
                return;
            }
            int cell;
            try {
                cell = Integer.parseInt(command.arguments().getFirst().trim());
            } catch (NumberFormatException e) {
                reply(command, context, "用法：/猜生物 <1-9> <生物名>，例如 /猜生物 5 僵尸（格子从左到右、从上到下编号）。");
                return;
            }
            String name = String.join(" ", command.arguments().subList(1, command.arguments().size())).trim();
            if (name.isEmpty()) {
                reply(command, context, "用法：/猜生物 <1-9> <生物名>，例如 /猜生物 5 僵尸。");
                return;
            }
            replyWithMobBoard(command, context, mobService.fill(command.event(), command.userId(), cell, name));
        } catch (RuntimeException e) {
            reply(command, context, "填格失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.mob.surrender", command = "结束猜生物", name = "结束本局猜生物", description = "投降并揭晓本局参考答案，结束后可立即开始新一局", allowAnonymous = true)
    public void mobSurrender(PluginCommandContext command, PluginContext context) {
        try {
            replyWithMobBoard(command, context, mobService.surrender(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    // ---------------------------------------------------------------- 猜合成（反向填配方）

    @PluginCommand(code = "mcguess.recipe.fill", command = "猜合成", name = "填一格原料", description = "猜目标物品配方中某一格的原料，例如 /猜合成 5 木板；不带参数时查看本局棋盘", allowAnonymous = true)
    public void recipeFill(PluginCommandContext command, PluginContext context) {
        try {
            if (command.arguments().isEmpty()) {
                replyWithRecipeBoard(command, context, recipeService.status(command.event()));
                return;
            }
            int cell;
            try {
                cell = Integer.parseInt(command.arguments().getFirst().trim());
            } catch (NumberFormatException e) {
                reply(command, context, "用法：/猜合成 <1-9> <物品名>，例如 /猜合成 5 木板（格子从左到右、从上到下编号）。");
                return;
            }
            String name = String.join(" ", command.arguments().subList(1, command.arguments().size())).trim();
            if (name.isEmpty()) {
                reply(command, context, "用法：/猜合成 <1-9> <物品名>，例如 /猜合成 5 木板。");
                return;
            }
            replyWithRecipeBoard(command, context, recipeService.fill(command.event(), command.userId(), cell, name));
        } catch (RuntimeException e) {
            reply(command, context, "填格失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.recipe.hint", command = "猜合成提示", name = "使用猜合成提示", description = "连续空猜 6 次后可用，随机揭示目标配方中的一格原料", allowAnonymous = true)
    public void recipeHint(PluginCommandContext command, PluginContext context) {
        try {
            replyWithRecipeBoard(command, context, recipeService.hint(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.recipe.surrender", command = "结束猜合成", name = "结束本局猜合成", description = "投降并揭晓本局配方，结束后可立即开始新一局", allowAnonymous = true)
    public void recipeSurrender(PluginCommandContext command, PluginContext context) {
        try {
            replyWithRecipeBoard(command, context, recipeService.surrender(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    // ---------------------------------------------------------------- 迷雾（图标渐显猜物）

    @PluginCommand(code = "mcguess.fog", command = "迷雾", name = "迷雾猜物", description = "图标被迷雾遮蔽，每猜错一次散一分，例如 /迷雾 钻石剑；不带参数时查看本局棋盘", allowAnonymous = true)
    public void fog(PluginCommandContext command, PluginContext context) {
        try {
            if (command.arguments().isEmpty()) {
                replyWithFogBoard(command, context, fogService.status(command.event()));
                return;
            }
            String input = String.join(" ", command.arguments());
            replyWithFogBoard(command, context, fogService.guess(command.event(), command.userId(), input));
        } catch (RuntimeException e) {
            reply(command, context, "猜测失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.fog.surrender", command = "结束迷雾", name = "结束本局迷雾", description = "投降并揭晓本局迷雾目标，结束后可立即开始新一局", allowAnonymous = true)
    public void fogSurrender(PluginCommandContext command, PluginContext context) {
        try {
            replyWithFogBoard(command, context, fogService.surrender(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    // ---------------------------------------------------------------- 比大小（出现次数连胜，个人）

    @PluginCommand(code = "mcguess.hol", command = "比大小", name = "比大小开局", description = "判断物品 B 在全部合成配方中的出现次数比 A 高还是低，连胜挑战（需绑定账号）")
    public void hol(PluginCommandContext command, PluginContext context) {
        try {
            replyWithHolBoard(command, context, holService.play(command.event(), command.userId()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.hol.higher", command = "高", name = "比大小选高", description = "判断 B 的出现次数比 A 高（需绑定账号）")
    public void holHigher(PluginCommandContext command, PluginContext context) {
        try {
            replyWithHolBoard(command, context, holService.answer(command.event(), command.userId(), true));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.hol.lower", command = "低", name = "比大小选低", description = "判断 B 的出现次数比 A 低（需绑定账号）")
    public void holLower(PluginCommandContext command, PluginContext context) {
        try {
            replyWithHolBoard(command, context, holService.answer(command.event(), command.userId(), false));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    // ---------------------------------------------------------------- 快答（合成计数抢答）

    @PluginCommand(code = "mcguess.quiz", command = "快答", name = "快答抢答", description = "回答当前快答题目的选项，例如 /快答 B；不带参数时查看本局局面", allowAnonymous = true)
    public void quiz(PluginCommandContext command, PluginContext context) {
        try {
            String input = command.arguments().isEmpty() ? null : command.arguments().getFirst();
            replyWithQuizBoard(command, context, quizService.answer(command.event(), command.userId(), input));
        } catch (RuntimeException e) {
            reply(command, context, "作答失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.quiz.surrender", command = "结束快答", name = "结束本局快答", description = "投降并公布本局全部答案，结束后可立即开始新一局", allowAnonymous = true)
    public void quizSurrender(PluginCommandContext command, PluginContext context) {
        try {
            replyWithQuizBoard(command, context, quizService.surrender(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    // ---------------------------------------------------------------- 宾果（5x5 连线）

    @PluginCommand(code = "mcguess.bingo", command = "宾果", name = "点亮宾果格", description = "报物品名点亮宾果棋盘对应格子，例如 /宾果 铁锭；不带参数时查看本局棋盘", allowAnonymous = true)
    public void bingo(PluginCommandContext command, PluginContext context) {
        try {
            String input = command.arguments().isEmpty() ? null : String.join(" ", command.arguments());
            replyWithBingoBoard(command, context, bingoService.claim(command.event(), command.userId(), input));
        } catch (RuntimeException e) {
            reply(command, context, "点亮失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.bingo.surrender", command = "结束宾果", name = "结束本局宾果", description = "投降结束本局宾果，结束后可立即开始新一局", allowAnonymous = true)
    public void bingoSurrender(PluginCommandContext command, PluginContext context) {
        try {
            replyWithBingoBoard(command, context, bingoService.surrender(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    // ---------------------------------------------------------------- 找茬（配方找错格）

    @PluginCommand(code = "mcguess.spot", command = "找茬", name = "指出错误格", description = "指出配方中被掉包的格子，例如 /找茬 5；不带参数时查看本局棋盘", allowAnonymous = true)
    public void spot(PluginCommandContext command, PluginContext context) {
        try {
            String input = command.arguments().isEmpty() ? null : command.arguments().getFirst();
            replyWithSpotBoard(command, context, spotService.answer(command.event(), command.userId(), input));
        } catch (RuntimeException e) {
            reply(command, context, "指认失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.spot.surrender", command = "结束找茬", name = "结束本局找茬", description = "投降并揭晓错误格，结束后可立即开始新一局", allowAnonymous = true)
    public void spotSurrender(PluginCommandContext command, PluginContext context) {
        try {
            replyWithSpotBoard(command, context, spotService.surrender(command.event()));
        } catch (RuntimeException e) {
            reply(command, context, "操作失败：" + safeMessage(e));
        }
    }

    // ---------------------------------------------------------------- 图鉴收集

    @PluginCommand(code = "mcguess.collection", command = "图鉴", name = "查看我的图鉴", description = "查看自己收集到的物品图鉴（需绑定账号）")
    public void collection(PluginCommandContext command, PluginContext context) {
        try {
            String text = collectionService.collectionText(command.userId());
            replyWithBoard(command, context, text,
                    collectionService.collectionVariables(command.userId(), text),
                    "mcguess-collection", "#mcguess-collection-card");
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.collection.leaderboard", command = "图鉴排行", name = "查看图鉴排行", description = "查看物品图鉴收集数量排行榜前十", allowAnonymous = true)
    public void collectionLeaderboard(PluginCommandContext command, PluginContext context) {
        try {
            reply(command, context, collectionService.collectionLeaderboardText());
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    // ---------------------------------------------------------------- 战绩与帮助

    @PluginCommand(code = "mcguess.my-stats", command = "猜物战绩", name = "查看个人战绩", description = "查看自己各模式的猜谜战绩与图鉴进度，需要先绑定系统账号")
    public void myStats(PluginCommandContext command, PluginContext context) {
        try {
            reply(command, context, statsService.myStats(command.userId()));
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.leaderboard", command = "猜物排行", name = "查看排行榜", description = "查看猜谜排行榜前十（按总胜场）", allowAnonymous = true)
    public void leaderboard(PluginCommandContext command, PluginContext context) {
        try {
            reply(command, context, statsService.leaderboard());
        } catch (RuntimeException e) {
            reply(command, context, "查询失败：" + safeMessage(e));
        }
    }

    @PluginCommand(code = "mcguess.rules", command = "猜物规则", name = "玩法说明", description = "查看 MC 猜谜全部模式的玩法与指令", allowAnonymous = true)
    public void rules(PluginCommandContext command, PluginContext context) {
        reply(command, context, """
                🎯 MC 猜谜玩法（群回合制，一局结束后可立即再开新局；JE 1.20.5 全物品数据集）

                【猜物】系统随机选定目标物品，猜测区域是它的 3x3 合成配方。
                /猜物 <物品名> — 提交猜测，例如 /猜物 钻石剑；不带参数查看本局棋盘
                /猜物格子 <1-9> — 查看已揭示格子中物品的合成配方
                /猜物提示 — 连续空猜 6 次后可用，随机揭示一格
                /结束猜物 — 投降并揭晓目标
                猜中配方树内的物品会显示与目标的距离（>4 显示「远」）与出现次数，直接原料会在配方格上揭示。

                【猜生物】3 行条件 × 3 列条件的 9 格棋盘，生物须同时满足行与列条件且同盘不重复。
                /猜生物 <1-9> <生物名> — 填格，例如 /猜生物 5 僵尸；填错扣 1 ❤️（共 6 颗）
                /结束猜生物 — 投降并揭晓参考答案

                【猜合成】目标物品公开，逐格猜它的 3x3 配方原料；猜中某格会一并揭示该物品占用的全部格子。
                /猜合成 <1-9> <物品名> — 填格，例如 /猜合成 5 木板
                /猜合成提示 — 连续空猜 6 次后可用，随机揭示一格原料
                /结束猜合成 — 投降并揭晓配方

                【迷雾】目标物品的图标被迷雾遮蔽，每次猜错迷雾散去一分（阶段 0-5，最终完全清晰），直接猜中目标即胜。
                /迷雾 <物品名> — 提交猜测，例如 /迷雾 钻石剑；不带参数查看本局棋盘
                /结束迷雾 — 投降并揭晓目标

                【快答】一局 5 道选择题：合成 1 个 X 总共需要几个 Y？四个选项，每题第一个答对的人得 1 分，
                答错的人本题不得再答；5 题全部答出后结算，得分最高者获胜。
                /快答 <A-D> — 抢答当前题目，例如 /快答 B；不带参数查看局面与计分板
                /结束快答 — 投降并公布全部答案

                【宾果】5x5 共享棋盘 25 格不同物品，报物品名点亮对应格子（智能匹配），
                率先点亮任意一整行 / 整列 / 对角线者获胜。
                /宾果 <物品名> — 点亮格子，例如 /宾果 铁锭；不带参数查看本局棋盘
                /结束宾果 — 投降结束本局

                【找茬】展示一个真实 3x3 配方，但某一格被掉包成了违和物品（常是同族变体），
                第一个指出错误格的人获胜。
                /找茬 <1-9> — 指出错误格，例如 /找茬 5；不带参数查看本局棋盘
                /结束找茬 — 投降并揭晓错误格

                【比大小】个人连胜挑战（需绑定账号）：物品 A 的出现次数已知，判断物品 B
                在全部合成配方中的出现次数比 A 更高还是更低；答对 B 晋级为新 A 且连胜 +1，答错连胜清零。
                /比大小 — 开局或查看当前局面
                /高、/低 — 作答

                【图鉴】游玩 猜物 / 猜合成 / 迷雾 / 找茬 获胜，或在宾果中点亮格子、快答中答对题目，
                都会把对应物品收入个人图鉴（需绑定账号）。
                /图鉴 — 查看我的收集进度
                /图鉴排行 — 收集数量排行榜前十

                /猜物战绩 — 个人战绩（需先 /绑定 系统账号）
                /猜物排行 — 排行榜前十（按总胜场）

                猜物 / 猜合成 / 迷雾 / 宾果支持智能匹配：可忽略颜色词（红色/白色…）、主世界木质词（橡木/云杉…）与材质词（染色/磨制/切制），
                例如「红色羊毛」可匹配「橙色羊毛」，「红色玻璃板」可匹配「紫色染色玻璃板」。""");
    }

    // ---------------------------------------------------------------- 回复与棋盘渲染

    private void reply(PluginCommandContext command, PluginContext context, String text) {
        String messageId = command.event().messageId();
        Map<String, Object> referrer = messageId == null || messageId.isBlank() ? Map.of() : Map.of("message_id", messageId);
        context.framework().messaging().send(new PluginMessageRequest(
                command.event().connectionId(), command.event().platform(), command.event().selfId(),
                command.event().channelId(),
                new PluginMessageContent(PluginMessageContent.Type.TEXT, text, null, referrer)));
    }

    private void replyWithItemBoard(PluginCommandContext command, PluginContext context, String text) {
        replyWithBoard(command, context, text,
                itemService.boardVariables(command.event(), text), "mcguess-board", "#mcguess-card");
    }

    private void replyWithMobBoard(PluginCommandContext command, PluginContext context, String text) {
        replyWithBoard(command, context, text,
                mobService.boardVariables(command.event(), text), "mcguess-mob-board", "#mcguess-mob-card");
    }

    private void replyWithRecipeBoard(PluginCommandContext command, PluginContext context, String text) {
        replyWithBoard(command, context, text,
                recipeService.boardVariables(command.event(), text), "mcguess-recipe-board", "#mcguess-recipe-board-card");
    }

    private void replyWithFogBoard(PluginCommandContext command, PluginContext context, String text) {
        replyWithBoard(command, context, text,
                fogService.boardVariables(command.event(), text), "mcguess-fog-board", "#mcguess-fog-card");
    }

    private void replyWithHolBoard(PluginCommandContext command, PluginContext context, String text) {
        replyWithBoard(command, context, text,
                holService.boardVariables(command.userId(), text), "mcguess-hol-board", "#mcguess-hol-card");
    }

    private void replyWithQuizBoard(PluginCommandContext command, PluginContext context, String text) {
        replyWithBoard(command, context, text,
                quizService.boardVariables(command.event(), text), "mcguess-quiz-board", "#mcguess-quiz-card");
    }

    private void replyWithBingoBoard(PluginCommandContext command, PluginContext context, String text) {
        replyWithBoard(command, context, text,
                bingoService.boardVariables(command.event(), text), "mcguess-bingo-board", "#mcguess-bingo-card");
    }

    private void replyWithSpotBoard(PluginCommandContext command, PluginContext context, String text) {
        replyWithBoard(command, context, text,
                spotService.boardVariables(command.event(), text), "mcguess-spot-board", "#mcguess-spot-card");
    }

    /**
     * 优先以棋盘图片回复：模板渲染成功时发送图片消息，不在群聊、渲染不可用或失败时降级为纯文本。
     */
    private void replyWithBoard(PluginCommandContext command, PluginContext context, String text,
                                Map<String, Object> variables, String template, String selector) {
        if (variables == null) {
            reply(command, context, text);
            return;
        }
        renderImage(command, context, template, variables, selector, text);
    }

    private void renderImage(PluginCommandContext command, PluginContext context, String template,
                             Map<String, Object> variables, String selector, String fallbackText) {
        var event = command.event();
        context.templateRenderer().render(template, variables, selector).whenComplete((image, error) -> {
            if (error != null || image == null || image.content() == null || image.content().length == 0) {
                reply(command, context, fallbackText);
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
