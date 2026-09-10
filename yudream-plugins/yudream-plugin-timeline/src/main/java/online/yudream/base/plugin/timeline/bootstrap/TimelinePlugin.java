package online.yudream.base.plugin.timeline.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.timeline.application.TimelineEventService;
import online.yudream.base.plugin.timeline.infrastructure.JsonSupport;
import online.yudream.base.plugin.timeline.infrastructure.TimelineEventRepository;
import online.yudream.base.plugin.timeline.interfaces.TimelineAdminController;
import online.yudream.base.plugin.timeline.interfaces.TimelineThemeBlockProvider;
import online.yudream.base.plugin.timeline.interfaces.TimelinePublicController;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.theme.PluginThemeBlockProvider;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

@PluginSpec(code = TimelinePlugin.CODE, name = "大事记", version = TimelinePlugin.VERSION,
        description = "组织大事记时间轴：记录并展示团队历史事件，支持图文、文字、换届、里程碑与荣誉等多种类型")
@PluginPermissions({
        @PluginPermission(code = TimelinePlugin.MANAGE_PERMISSION, name = "管理大事记", module = "大事记",
                description = "维护时间轴事件：创建、编辑、发布/下架与删除")
})
@PluginFrontend(moduleName = "timeline", menuTitle = "大事记", menuIcon = "i-ri:time-line", menuSort = 74, styles = {"style.css"}, routes = {
        @PluginRoute(path = "/platform/plugins/timeline/admin", name = "platform-plugin-timeline-admin", title = "事件管理",
                icon = "i-ri:calendar-event-line", component = "timeline/Admin", permission = TimelinePlugin.MANAGE_PERMISSION, sort = 10),
        @PluginRoute(path = "/timeline", name = "timeline-public", title = "大事记",
                icon = "i-ri:time-line", component = "timeline/Public", hideInMenu = true, publicAccess = true, siteNav = true)
})
public final class TimelinePlugin implements YuDreamPlugin {
    public static final String CODE = "timeline";
    public static final String VERSION = "1.3.0";
    public static final String MANAGE_PERMISSION = "plugin:timeline:manage";

    @Override
    public void onEnable(PluginContext context) {
        JsonSupport json = new JsonSupport(new ObjectMapper());
        TimelineEventRepository events = new TimelineEventRepository(context.documents());
        TimelineEventService eventService = new TimelineEventService(events);
        context.registerHttpController(new TimelinePublicController(eventService));
        context.registerHttpController(new TimelineAdminController(eventService, json));
        context.registerExtension(PluginThemeBlockProvider.class, new TimelineThemeBlockProvider(eventService));
    }
}
