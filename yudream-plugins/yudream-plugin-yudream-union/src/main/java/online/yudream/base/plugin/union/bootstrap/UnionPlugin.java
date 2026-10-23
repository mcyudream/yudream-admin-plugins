package online.yudream.base.plugin.union.bootstrap;

import online.yudream.base.plugin.union.api.UnionFederationService;
import online.yudream.base.plugin.union.application.service.FederationJoinService;
import online.yudream.base.plugin.union.application.service.UnionBanService;
import online.yudream.base.plugin.union.application.service.UnionDirectoryService;
import online.yudream.base.plugin.union.application.service.UnionDiscoveryService;
import online.yudream.base.plugin.union.infrastructure.PeerHttpClient;
import online.yudream.base.plugin.union.infrastructure.UnionCryptoService;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.interfaces.controller.UnionAdminController;
import online.yudream.base.plugin.union.interfaces.controller.UnionPeerController;
import online.yudream.base.plugin.union.interfaces.http.UnionHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginDashboardCard;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@PluginSpec(
        code = UnionPlugin.CODE,
        name = "YD 联合认证",
        version = "0.1.0",
        description = "去中心化联合认证：受信站点互信注册、跨站 join 断言验签与角色名联合封禁。"
)
@PluginPermissions({
        @PluginPermission(code = UnionPlugin.VIEW_PERMISSION, name = "查看联合认证", module = "联合认证",
                description = "查看受信站点、封禁账本与审计"),
        @PluginPermission(code = UnionPlugin.MANAGE_PERMISSION, name = "管理联合认证", module = "联合认证",
                description = "信任注册、站点启停、封禁管理与账本拉取"),
        @PluginPermission(code = UnionPlugin.REVOKE_PERMISSION, name = "解除联合封禁", module = "联合认证",
                description = "解除联合封禁条目")
})
@PluginDashboardCard(
        code = "union",
        title = "联合认证",
        description = "受信站点互信与跨站 join 断言验签服务。",
        icon = "i-ri:links-line",
        category = "认证服务",
        component = "yudream-union/UnionCard",
        actionPath = "/platform/plugins/yudream-union/admin/peers",
        dragPayloadTemplate = "yudream-union:federation:{encodedUrl}",
        tone = "violet",
        defaultW = 4,
        defaultH = 2,
        minW = 3,
        minH = 2,
        sort = 36
)
@PluginFrontend(
        moduleName = "yudreamUnion",
        menuTitle = "联合认证",
        menuIcon = "i-ri:links-line",
        menuSort = 37,
        styles = {"style.css"},
        routes = {
                @PluginRoute(path = "/platform/plugins/yudream-union/admin/settings", name = "platform-plugin-yudream-union-admin-settings",
                        title = "站点身份", icon = "i-ri:links-line", component = "yudream-union/AdminSettings",
                        permission = UnionPlugin.VIEW_PERMISSION,
                        parentPath = "/platform/plugins/yudream-union/admin", parentTitle = "联合认证管理",
                        parentIcon = "i-ri:links-line", parentSort = 10,
                        sort = 10),
                @PluginRoute(path = "/platform/plugins/yudream-union/admin/peers", name = "platform-plugin-yudream-union-admin-peers",
                        title = "受信站点", icon = "i-ri:server-line", component = "yudream-union/AdminPeers",
                        permission = UnionPlugin.VIEW_PERMISSION,
                        parentPath = "/platform/plugins/yudream-union/admin", parentTitle = "联合认证管理",
                        parentIcon = "i-ri:links-line", parentSort = 10,
                        sort = 20),
                @PluginRoute(path = "/platform/plugins/yudream-union/admin/federation", name = "platform-plugin-yudream-union-admin-federation",
                        title = "跨站登录", icon = "i-ri:global-line", component = "yudream-union/AdminFederation",
                        permission = UnionPlugin.VIEW_PERMISSION,
                        parentPath = "/platform/plugins/yudream-union/admin", parentTitle = "联合认证管理",
                        parentIcon = "i-ri:links-line", parentSort = 10,
                        sort = 30),
                @PluginRoute(path = "/platform/plugins/yudream-union/admin/bans", name = "platform-plugin-yudream-union-admin-bans",
                        title = "联合封禁", icon = "i-ri:forbid-line", component = "yudream-union/AdminBans",
                        permission = UnionPlugin.VIEW_PERMISSION,
                        parentPath = "/platform/plugins/yudream-union/admin", parentTitle = "联合认证管理",
                        parentIcon = "i-ri:links-line", parentSort = 10,
                        sort = 40),
                @PluginRoute(path = "/platform/plugins/yudream-union/admin/audit", name = "platform-plugin-yudream-union-admin-audit",
                        title = "审计", icon = "i-ri:file-list-3-line", component = "yudream-union/AdminAudit",
                        permission = UnionPlugin.VIEW_PERMISSION,
                        parentPath = "/platform/plugins/yudream-union/admin", parentTitle = "联合认证管理",
                        parentIcon = "i-ri:links-line", parentSort = 10,
                        sort = 50)
        }
)
public class UnionPlugin implements YuDreamPlugin {

    public static final String CODE = "yudream-union";
    public static final String VIEW_PERMISSION = "plugin:yudream-union:view";
    public static final String MANAGE_PERMISSION = "plugin:yudream-union:manage";
    public static final String REVOKE_PERMISSION = "plugin:yudream-union:revoke";

    @Override
    public void onEnable(PluginContext context) {
        UnionRepository repository = new UnionRepository(context.documents());
        PeerHttpClient peerHttpClient = new PeerHttpClient();
        UnionCryptoService cryptoService = new UnionCryptoService(context, repository);
        // 联合根私钥必须在 SecretStore 可用时创建/读取；不可安全存储则 fail-closed，不暴露半可用协议端点。
        cryptoService.keyPair();
        UnionDiscoveryService discovery = new UnionDiscoveryService(context, repository, cryptoService);
        UnionBanService banService = new UnionBanService(repository, peerHttpClient, cryptoService);
        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "union-federation");
            thread.setDaemon(true);
            return thread;
        });
        context.onDispose(executor::shutdownNow);
        FederationJoinService federation = new FederationJoinService(
                context, repository, cryptoService, peerHttpClient, banService, discovery, executor);
        UnionDirectoryService directory = new UnionDirectoryService(repository, cryptoService, peerHttpClient, discovery);
        UnionHttpFacade facade = new UnionHttpFacade(federation, banService, directory, discovery, repository);
        context.registerHttpController(new UnionPeerController(facade));
        context.registerHttpController(new UnionAdminController(facade));
        // A 侧验签入口：authlib-injector（softdepend 本插件）在 hasJoined 未命中时消费。
        context.exposeService(UnionFederationService.class, federation);
        // 保留清理：审计默认 30 天、跨站登录事件默认 90 天（0 = 永久），启动后 1 分钟首跑、此后每日一次。
        java.util.concurrent.ScheduledExecutorService retention = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "union-retention");
            thread.setDaemon(true);
            return thread;
        });
        Runnable purge = () -> {
            try {
                long auditDays = retentionDays(repository, "auditDays", 30);
                long joinDays = retentionDays(repository, "joinDays", 90);
                long now = System.currentTimeMillis();
                long auditDeleted = auditDays > 0 ? repository.purgeAuditBefore(now - auditDays * 24 * 3600_000L) : 0;
                long joinDeleted = joinDays > 0 ? repository.purgeJoinEventsBefore(now - joinDays * 24 * 3600_000L) : 0;
                if (auditDeleted + joinDeleted > 0) {
                    System.out.printf("[yudream-union] retention purge: audit=%d, joinEvents=%d%n", auditDeleted, joinDeleted);
                }
            } catch (RuntimeException ignored) {
                // 清理失败不影响主流程，下一轮重试
            }
        };
        retention.scheduleWithFixedDelay(purge, 60, 24 * 3600, java.util.concurrent.TimeUnit.SECONDS);
        context.onDispose(retention::shutdownNow);
    }

    private static long retentionDays(UnionRepository repository, String key, long fallback) {
        return repository.setting("retention")
                .map(document -> document.get(key))
                .map(value -> {
                    try {
                        long parsed = Long.parseLong(String.valueOf(value));
                        return parsed >= 0 ? parsed : fallback;
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }
}
