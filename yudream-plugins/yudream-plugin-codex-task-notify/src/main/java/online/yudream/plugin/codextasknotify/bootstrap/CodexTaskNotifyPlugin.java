package online.yudream.plugin.codextasknotify.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.plugin.codextasknotify.application.service.CodexTaskNotificationService;
import online.yudream.plugin.codextasknotify.application.service.CodexTaskSessionService;
import online.yudream.plugin.codextasknotify.infrastructure.repository.CodexTaskSessionRepository;
import online.yudream.plugin.codextasknotify.interfaces.controller.CodexTaskNotifyController;
import online.yudream.plugin.codextasknotify.interfaces.http.CodexTaskNotifyHttpFacade;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@PluginSpec(
        code = CodexTaskNotifyPlugin.CODE,
        name = "Codex 任务通知",
        version = "1.0.0",
        description = "将 Codex 任务状态私信给 API Key 所属用户"
)
@PluginPermission(
        code = CodexTaskNotifyPlugin.SEND_PERMISSION,
        name = "发送 Codex 任务通知",
        module = "平台插件",
        description = "允许 API Key 所属用户接收 Codex 任务完成、确认与中断通知"
)
public class CodexTaskNotifyPlugin implements YuDreamPlugin {

    public static final String CODE = "codex-task-notify";
    public static final String SEND_PERMISSION = "plugin:codex-task-notify:send";
    private static final Logger LOGGER = Logger.getLogger(CodexTaskNotifyPlugin.class.getName());

    @Override
    public void onEnable(PluginContext context) {
        CodexTaskNotificationService notifications = new CodexTaskNotificationService(context.framework().messaging());
        CodexTaskSessionService sessions = new CodexTaskSessionService(
                new CodexTaskSessionRepository(context.documents()), notifications
        );
        context.registerHttpController(new CodexTaskNotifyController(new CodexTaskNotifyHttpFacade(notifications, sessions)));
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        watchdog.scheduleWithFixedDelay(() -> {
            try {
                sessions.expireDueSessions(System.currentTimeMillis());
            } catch (RuntimeException exception) {
                LOGGER.warning("[Codex Task Notify] heartbeat watchdog scan failed");
            }
        }, 15, 15, TimeUnit.SECONDS);
        context.onDispose(watchdog::shutdownNow);
    }
}
