package online.yudream.base.plugin.feedback.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.feedback.application.service.FeedbackAppService;
import online.yudream.base.plugin.feedback.infrastructure.JsonSupport;
import online.yudream.base.plugin.feedback.infrastructure.repository.FeedbackDocumentRepository;
import online.yudream.base.plugin.feedback.interfaces.controller.FeedbackAdminController;
import online.yudream.base.plugin.feedback.interfaces.controller.FeedbackUserController;
import online.yudream.base.plugin.feedback.interfaces.http.FeedbackHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.user.PluginUserService;

/**
 * 问题反馈插件入口：只负责装配、注册与生命周期，业务流程全部下沉到应用层。
 *
 * <p>面向用户的是「提交 / 跟进自己的反馈」，面向管理员的是「跨用户受理、回复、指派与流转」，
 * 两条链路在权限、路由、接口、用例与数据范围上完全分离。
 */
@PluginSpec(
        code = FeedbackPlugin.CODE,
        name = FeedbackPlugin.CODE,
        version = FeedbackPlugin.VERSION,
        description = "问题反馈：用户提交缺陷、建议与咨询并跟进处理进度、在管理员给出结论后归档，"
                + "管理员跨用户受理、回复、指派处理人（支持多人协作）并流转状态，"
                + "结论超过 7 天未归档的由系统自动归档，全部处理过程留痕可回溯。",
        icon = "i-ri:feedback-line"
)
@PluginPermissions({
        @PluginPermission(code = FeedbackPlugin.USER_PERMISSION, name = "使用问题反馈", module = "平台插件",
                description = "提交反馈、查看并跟进自己的反馈处理进度、追加说明，并在管理员给出结论后归档"),
        @PluginPermission(code = FeedbackPlugin.MANAGE_PERMISSION, name = "管理问题反馈", module = "平台插件",
                description = "跨用户受理反馈、回复、指派处理人、填写内部备注与流转状态")
})
@PluginFrontend(
        moduleName = "feedback",
        menuTitle = "问题反馈",
        menuIcon = "i-ri:feedback-line",
        menuSort = 23,
        styles = {"style.css"},
        routes = {
                @PluginRoute(
                        path = "/platform/plugins/feedback/me/feedbacks",
                        name = "platform-plugin-feedback-me-feedbacks",
                        title = "我的反馈",
                        icon = "i-ri:chat-quote-line",
                        component = "feedback/MyFeedbacks",
                        permission = FeedbackPlugin.USER_PERMISSION,
                        sort = 60
                ),
                @PluginRoute(
                        path = "/platform/plugins/feedback/me/feedbacks/new",
                        name = "platform-plugin-feedback-me-new",
                        title = "提交反馈",
                        icon = "i-ri:add-circle-line",
                        component = "feedback/NewFeedback",
                        permission = FeedbackPlugin.USER_PERMISSION,
                        sort = 61
                ),
                @PluginRoute(
                        path = "/platform/plugins/feedback/me/feedbacks/:id",
                        name = "platform-plugin-feedback-me-detail",
                        title = "反馈详情",
                        icon = "i-ri:file-text-line",
                        component = "feedback/FeedbackDetail",
                        permission = FeedbackPlugin.USER_PERMISSION,
                        hideInMenu = true,
                        sort = 62
                ),
                @PluginRoute(
                        path = "/platform/plugins/feedback/me/feedbacks/:id/edit",
                        name = "platform-plugin-feedback-me-edit",
                        title = "修改反馈",
                        icon = "i-ri:edit-line",
                        component = "feedback/FeedbackEdit",
                        permission = FeedbackPlugin.USER_PERMISSION,
                        hideInMenu = true,
                        sort = 63
                ),
                @PluginRoute(
                        path = "/platform/plugins/feedback/admin/feedbacks",
                        name = "platform-plugin-feedback-admin-feedbacks",
                        title = "反馈管理",
                        icon = "i-ri:inbox-archive-line",
                        component = "feedback/AdminFeedbacks",
                        permission = FeedbackPlugin.MANAGE_PERMISSION,
                        sort = 20
                ),
                @PluginRoute(
                        path = "/platform/plugins/feedback/admin/feedbacks/:id",
                        name = "platform-plugin-feedback-admin-detail",
                        title = "反馈处理",
                        icon = "i-ri:clipboard-line",
                        component = "feedback/AdminFeedbackDetail",
                        permission = FeedbackPlugin.MANAGE_PERMISSION,
                        hideInMenu = true,
                        sort = 21
                )
        }
)
public class FeedbackPlugin implements YuDreamPlugin {

    public static final String CODE = "feedback";
    public static final String VERSION = "1.4.1";
    public static final String USER_PERMISSION = "plugin:feedback:use";
    public static final String MANAGE_PERMISSION = "plugin:feedback:manage";

    private static final Logger LOGGER = Logger.getLogger(FeedbackPlugin.class.getName());
    /** 自动归档扫描间隔（秒）：宽限期以天计，不需要更密的检查。 */
    private static final long TICK_SECONDS = 1800;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Override
    public void onEnable(PluginContext context) {
        JsonSupport json = new JsonSupport(new ObjectMapper());
        FeedbackDocumentRepository feedbacks = new FeedbackDocumentRepository(context.documents());
        FeedbackAppService appService = new FeedbackAppService(feedbacks, userDirectory(context));
        FeedbackHttpFacade http = new FeedbackHttpFacade(appService, json);
        context.registerHttpController(new FeedbackUserController(http));
        context.registerHttpController(new FeedbackAdminController(http));

        // 每半小时检查一次到期的自动归档：宽限期以天计，检查频率稍疏即可，
        // 单轮只扫描结论时间，命中才写库。
        scheduler.scheduleWithFixedDelay(() -> sweepAutoArchive(appService),
                TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        context.onDispose(scheduler::shutdownNow);
    }

    /** 定时任务体：异常必须就地吞掉，否则调度线程会静默停止后续所有轮次。 */
    private void sweepAutoArchive(FeedbackAppService appService) {
        try {
            int archived = appService.autoArchiveExpired(System.currentTimeMillis());
            if (archived > 0) {
                LOGGER.info("[Feedback] 自动归档 " + archived + " 条超过宽限期未归档的反馈");
            }
        }
        catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[Feedback] 自动归档执行失败", e);
        }
    }

    /** 宿主用户目录不可用时降级为不解析昵称与不指派处理人，插件主体仍可运行。 */
    private static PluginUserService userDirectory(PluginContext context) {
        try {
            return context.framework() == null ? null : context.framework().users();
        }
        catch (RuntimeException e) {
            return null;
        }
    }
}
