package online.yudream.base.plugin.questionbank.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.questionbank.application.AdminRecordService;
import online.yudream.base.plugin.questionbank.application.AiGraderService;
import online.yudream.base.plugin.questionbank.application.AiImportJobService;
import online.yudream.base.plugin.questionbank.application.AiImportService;
import online.yudream.base.plugin.questionbank.application.CategoryService;
import online.yudream.base.plugin.questionbank.application.PaperService;
import online.yudream.base.plugin.questionbank.application.PracticeService;
import online.yudream.base.plugin.questionbank.application.QuestionService;
import online.yudream.base.plugin.questionbank.application.QuizScoreService;
import online.yudream.base.plugin.questionbank.application.SettingsService;
import online.yudream.base.plugin.questionbank.infrastructure.CategoryRepository;
import online.yudream.base.plugin.questionbank.infrastructure.JsonSupport;
import online.yudream.base.plugin.questionbank.infrastructure.PaperRepository;
import online.yudream.base.plugin.questionbank.infrastructure.QuestionRepository;
import online.yudream.base.plugin.questionbank.infrastructure.QuizScoreRepository;
import online.yudream.base.plugin.questionbank.infrastructure.SessionRepository;
import online.yudream.base.plugin.questionbank.interfaces.QuestionBankAdminController;
import online.yudream.base.plugin.questionbank.interfaces.QuestionBankMeController;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(code = QuestionBankPlugin.CODE, name = "题库", version = QuestionBankPlugin.VERSION,
        description = "在线题库：单选/多选/判断/填空/简答，Markdown 图文题干，随机抽题作答、自动判分、标签分类与导入导出")
@PluginPermissions({
        @PluginPermission(code = QuestionBankPlugin.VIEW_PERMISSION, name = "使用题库", module = "题库",
                description = "按分类/标签/题型/难度抽题练习，查看自己的作答记录"),
        @PluginPermission(code = QuestionBankPlugin.COMPOSE_PERMISSION, name = "组卷", module = "题库",
                description = "随机/手动组卷、导出 Word 与抢答大屏放映，不能维护题目与题库设置"),
        @PluginPermission(code = QuestionBankPlugin.MANAGE_PERMISSION, name = "管理题库", module = "题库",
                description = "题目/分类/标签维护、导入导出与跨用户练习记录")
})
@PluginFrontend(moduleName = "questionbank", menuTitle = "题库", menuIcon = "i-ri:questionnaire-line", menuSort = 73, routes = {
        @PluginRoute(path = "/platform/plugins/questionbank", name = "platform-plugin-questionbank-practice", title = "题库练习",
                icon = "i-ri:questionnaire-line", component = "questionbank/Practice", permission = QuestionBankPlugin.VIEW_PERMISSION, sort = 10),
        @PluginRoute(path = "/platform/plugins/questionbank/session", name = "platform-plugin-questionbank-session", title = "作答",
                component = "questionbank/Session", permission = QuestionBankPlugin.VIEW_PERMISSION, hideInMenu = true),
        @PluginRoute(path = "/platform/plugins/questionbank/records", name = "platform-plugin-questionbank-records", title = "我的成绩",
                icon = "i-ri:file-list-3-line", component = "questionbank/Records", permission = QuestionBankPlugin.VIEW_PERMISSION, sort = 20),
        @PluginRoute(path = "/platform/plugins/questionbank/papers", name = "platform-plugin-questionbank-papers", title = "题单",
                icon = "i-ri:file-paper-2-line", component = "questionbank/Papers", permission = QuestionBankPlugin.VIEW_PERMISSION, sort = 15),
        @PluginRoute(path = "/platform/plugins/questionbank/quiz-rank", name = "platform-plugin-questionbank-quiz-rank", title = "抢答排行",
                icon = "i-ri:trophy-line", component = "questionbank/QuizRank", permission = QuestionBankPlugin.VIEW_PERMISSION, sort = 16),
        @PluginRoute(path = "/platform/plugins/questionbank/admin", name = "platform-plugin-questionbank-admin", title = "题目管理",
                icon = "i-ri:archive-stack-line", component = "questionbank/Admin", permission = QuestionBankPlugin.MANAGE_PERMISSION, sort = 90),
        @PluginRoute(path = "/platform/plugins/questionbank/admin/questions/edit", name = "platform-plugin-questionbank-admin-question-edit", title = "编辑题目",
                component = "questionbank/QuestionEdit", permission = QuestionBankPlugin.MANAGE_PERMISSION, hideInMenu = true),
        @PluginRoute(path = "/platform/plugins/questionbank/admin/categories", name = "platform-plugin-questionbank-admin-categories", title = "分类管理",
                icon = "i-ri:price-tag-3-line", component = "questionbank/Categories", permission = QuestionBankPlugin.MANAGE_PERMISSION, sort = 91),
        @PluginRoute(path = "/platform/plugins/questionbank/admin/papers", name = "platform-plugin-questionbank-admin-papers", title = "题单管理",
                icon = "i-ri:file-paper-2-line", component = "questionbank/AdminPapers", permission = QuestionBankPlugin.MANAGE_PERMISSION, sort = 92),
        @PluginRoute(path = "/platform/plugins/questionbank/admin/papers/edit", name = "platform-plugin-questionbank-admin-paper-edit", title = "编辑题单",
                component = "questionbank/AdminPaperEdit", permission = QuestionBankPlugin.MANAGE_PERMISSION, hideInMenu = true),
        @PluginRoute(path = "/platform/plugins/questionbank/admin/compose", name = "platform-plugin-questionbank-admin-compose", title = "组卷中心",
                icon = "i-ri:file-copy-2-line", component = "questionbank/AdminCompose", permission = QuestionBankPlugin.COMPOSE_PERMISSION, sort = 89),
        @PluginRoute(path = "/platform/plugins/questionbank/admin/papers/print", name = "platform-plugin-questionbank-admin-paper-print", title = "打印题单",
                component = "questionbank/AdminPaperPrint", permission = QuestionBankPlugin.MANAGE_PERMISSION, hideInMenu = true, publicAccess = true),
        @PluginRoute(path = "/platform/plugins/questionbank/screen", name = "platform-plugin-questionbank-screen", title = "抢答大屏",
                component = "questionbank/BuzzScreen", permission = QuestionBankPlugin.COMPOSE_PERMISSION, hideInMenu = true, publicAccess = true),
        @PluginRoute(path = "/platform/plugins/questionbank/shared", name = "platform-plugin-questionbank-shared", title = "分享的组卷",
                component = "questionbank/SharedCompose", hideInMenu = true, publicAccess = true),
        @PluginRoute(path = "/platform/plugins/questionbank/admin/review", name = "platform-plugin-questionbank-admin-review", title = "简答审核",
                icon = "i-ri:quill-pen-line", component = "questionbank/AdminReview", permission = QuestionBankPlugin.MANAGE_PERMISSION, sort = 93),
        @PluginRoute(path = "/platform/plugins/questionbank/admin/settings", name = "platform-plugin-questionbank-admin-settings", title = "题库设置",
                icon = "i-ri:settings-3-line", component = "questionbank/AdminSettings", permission = QuestionBankPlugin.MANAGE_PERMISSION, sort = 95),
        @PluginRoute(path = "/platform/plugins/questionbank/admin/records", name = "platform-plugin-questionbank-admin-records", title = "练习记录",
                icon = "i-ri:history-line", component = "questionbank/AdminRecords", permission = QuestionBankPlugin.MANAGE_PERMISSION, sort = 94)
})
public final class QuestionBankPlugin implements YuDreamPlugin {
    public static final String CODE = "questionbank";
    public static final String VERSION = "1.2.1";
    public static final String VIEW_PERMISSION = "plugin:questionbank:view";
    public static final String COMPOSE_PERMISSION = "plugin:questionbank:compose";
    public static final String MANAGE_PERMISSION = "plugin:questionbank:manage";
    /** 自由刷题入口路由路径，菜单显隐随 practiceEnabled 开关联动。 */
    private static final String PRACTICE_ROUTE_PATH = "/platform/plugins/questionbank";

    @Override
    public void onEnable(PluginContext context) {
        ObjectMapper mapper = new ObjectMapper();
        JsonSupport json = new JsonSupport(mapper);

        QuestionRepository questions = new QuestionRepository(context.documents());
        CategoryRepository categories = new CategoryRepository(context.documents());
        SessionRepository sessions = new SessionRepository(context.documents());
        PaperRepository paperRepository = new PaperRepository(context.documents());

        CategoryService categoryService = new CategoryService(categories, questions, paperRepository);
        QuestionService questionService = new QuestionService(questions, categoryService, json, context.framework());
        PaperService paperService = new PaperService(paperRepository, questions);
        SettingsService settingsService = new SettingsService(context.documents());
        settingsService.onPracticeToggle(enabled -> syncPracticeMenuVisibility(context, enabled));
        AiGraderService aiGraderService = new AiGraderService(sessions, settingsService, context.framework());
        AiImportService aiImportService = new AiImportService(settingsService, context.framework(), json);
        AiImportJobService aiImportJobService = new AiImportJobService(context.framework(),
                settingsService, aiImportService, questionService);
        context.onDispose(aiImportJobService.shutdownHook());
        context.registerAiTool(new online.yudream.base.plugin.questionbank.infrastructure.CreateQuestionAiTool(
                questionService, json));
        PracticeService practiceService = new PracticeService(questions, categories, sessions,
                paperService, settingsService, context.framework(), aiGraderService);
        AdminRecordService recordService = new AdminRecordService(sessions);
        QuizScoreService quizScoreService = new QuizScoreService(new QuizScoreRepository(context.documents()),
                context.framework());

        context.exposeService(online.yudream.base.plugin.questionbank.api.QuestionBankApi.class,
                new online.yudream.base.plugin.questionbank.application.DefaultQuestionBankApi(practiceService, categoryService));

        context.registerHttpController(new QuestionBankMeController(practiceService, paperService, quizScoreService, json));
        context.registerHttpController(new QuestionBankAdminController(questionService, categoryService,
                recordService, paperService, settingsService, aiImportService, aiImportJobService, json));

        online.yudream.base.plugin.questionbank.application.ComposeService composeService =
                new online.yudream.base.plugin.questionbank.application.ComposeService(questions, context.framework());
        online.yudream.base.plugin.questionbank.application.ComposeRecordService composeRecordService =
                new online.yudream.base.plugin.questionbank.application.ComposeRecordService(
                        new online.yudream.base.plugin.questionbank.infrastructure.ComposeRecordRepository(context.documents()));
        context.registerHttpController(new online.yudream.base.plugin.questionbank.interfaces.QuestionBankComposeController(
                questionService, categoryService, composeService, json));
        context.registerHttpController(new online.yudream.base.plugin.questionbank.interfaces.QuestionBankComposeRecordController(
                composeRecordService, composeService, questionService, categoryService, json));
        context.registerHttpController(new online.yudream.base.plugin.questionbank.interfaces.QuestionBankSharedController(
                composeRecordService, composeService, categoryService));
        context.registerHttpController(new online.yudream.base.plugin.questionbank.interfaces.QuestionBankScreenController(
                composeService, paperService, categoryService, json, composeRecordService));

        online.yudream.base.plugin.questionbank.application.QqQuizService qqQuizService =
                new online.yudream.base.plugin.questionbank.application.QqQuizService(questions, settingsService,
                        quizScoreService, context.framework());
        this.qqQuizService = qqQuizService;
        context.onDispose(qqQuizService);
        context.onDispose(context.interactions().onMessage(
                new online.yudream.base.plugin.spi.system.messaging.PluginInteractionFilter(
                        java.util.Set.of("message_receive", "message"), null, null, null),
                event -> qqQuizService.onMessage(event, context)));

        // 启用时按当前开关同步刷题菜单显隐（菜单投影在 onEnable 之后，已存在的记录会被保留可见性）
        syncPracticeMenuVisibility(context, settingsService.practiceEnabled());
    }

    /** 刷题菜单随开关显隐；宿主 SPI 低于 2.17.0（无 setMenuVisible）时静默降级，仅关闭功能入口。 */
    private static void syncPracticeMenuVisibility(PluginContext context, boolean practiceEnabled) {
        try {
            context.setMenuVisible(PRACTICE_ROUTE_PATH, practiceEnabled);
        } catch (LinkageError ignored) {
            // 旧宿主无该 SPI 方法：菜单保持原状，功能仍由后端 requirePracticeEnabled 兜底
        }
    }

    private online.yudream.base.plugin.questionbank.application.QqQuizService qqQuizService;

    @online.yudream.base.plugin.spi.annotation.PluginCommand(
            code = "questionbank.random", command = "抽题", name = "题库抽题",
            description = "随机抽一道题发起群内抢答；可用「抽题 <分组名>」指定分组（分组在题库设置中配置）")
    public void quizCommand(online.yudream.base.plugin.spi.system.command.PluginCommandContext command,
                            PluginContext context) {
        if (qqQuizService != null) {
            qqQuizService.startQuiz(command, context);
        }
    }

    @online.yudream.base.plugin.spi.annotation.PluginCommand(
            code = "questionbank.quiz-rank", command = "抢答榜", name = "抢答排行榜",
            description = "查看本群抢答累计答对排行榜（前 10 名）")
    public void quizRankCommand(online.yudream.base.plugin.spi.system.command.PluginCommandContext command,
                                PluginContext context) {
        if (qqQuizService != null) {
            qqQuizService.leaderboard(command, context);
        }
    }
}
