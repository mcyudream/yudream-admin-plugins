package online.yudream.plugin.qqbotautomation.bootstrap;

import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.messaging.PluginInteractionFilter;
import online.yudream.plugin.qqbotautomation.application.service.AutomationPolicyService;
import online.yudream.plugin.qqbotautomation.application.service.JoinVerificationService;
import online.yudream.plugin.qqbotautomation.application.service.MediaJobService;
import online.yudream.plugin.qqbotautomation.interfaces.controller.QqbotAutomationController;
import online.yudream.plugin.qqbotautomation.interfaces.http.QqbotAutomationHttpFacade;

import java.util.Set;

@PluginSpec(code = QqbotAutomationPlugin.CODE, name = "QQ 群自动化", version = "1.0.0", description = "群申请验证与媒体链接处理")
@PluginPermissions({@PluginPermission(code = QqbotAutomationPlugin.MANAGE_PERMISSION, name = "管理 QQ 群自动化", module = "平台插件", description = "维护群策略、媒体任务与自动审核")})
@PluginFrontend(moduleName = "qqbotAutomation", menuTitle = "QQ 群自动化", menuIcon = "i-ri:chat-settings-line", menuSort = 66, routes = {
        @PluginRoute(path = "/platform/plugins/qqbot-automation/admin/policies", name = "platform-plugin-qqbot-automation-policies", title = "群自动化策略", icon = "i-ri:settings-3-line", component = "qqbot-automation/Policies", permission = QqbotAutomationPlugin.MANAGE_PERMISSION, sort = 10),
        @PluginRoute(path = "/platform/plugins/qqbot-automation/admin/media-jobs", name = "platform-plugin-qqbot-automation-media-jobs", title = "媒体任务", icon = "i-ri:download-cloud-2-line", component = "qqbot-automation/MediaJobs", permission = QqbotAutomationPlugin.MANAGE_PERMISSION, sort = 20)
})
public class QqbotAutomationPlugin implements YuDreamPlugin {
    public static final String CODE = "qqbot-automation";
    public static final String MANAGE_PERMISSION = "plugin:qqbot-automation:manage";
    @Override public void onEnable(PluginContext context) {
        AutomationPolicyService policies = new AutomationPolicyService(context.documents());
        JoinVerificationService verification = new JoinVerificationService(policies, context.framework());
        MediaJobService mediaJobs = new MediaJobService(policies, context.documents(), context.framework());
        context.registerHttpController(new QqbotAutomationController(new QqbotAutomationHttpFacade(policies, mediaJobs, context.framework())));
        context.interactions().onNative(new PluginInteractionFilter(Set.of("group_request"), "milky", null, null), verification::handle);
        context.interactions().onMessage(new PluginInteractionFilter(Set.of("message_receive"), "milky", null, null), mediaJobs::handle);
    }
}
