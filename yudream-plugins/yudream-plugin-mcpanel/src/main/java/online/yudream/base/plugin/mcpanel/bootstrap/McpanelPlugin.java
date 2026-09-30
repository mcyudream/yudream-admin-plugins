package online.yudream.base.plugin.mcpanel.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.application.cmd.NodeQueryCmd;
import online.yudream.base.plugin.mcpanel.application.port.NodeControlPlane;
import online.yudream.base.plugin.mcpanel.application.service.ContributionService;
import online.yudream.base.plugin.mcpanel.application.service.DomainService;
import online.yudream.base.plugin.mcpanel.application.service.McpanelEnrollService;
import online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.application.service.McpanelNodeAppService;
import online.yudream.base.plugin.mcpanel.application.service.ModpackService;
import online.yudream.base.plugin.mcpanel.application.service.SettingsService;
import online.yudream.base.plugin.mcpanel.application.service.TemplateService;
import online.yudream.base.plugin.mcpanel.application.service.TenantScopeService;
import online.yudream.base.plugin.mcpanel.domain.repo.EnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import online.yudream.base.plugin.mcpanel.domain.service.PortAllocator;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeConnectionManager;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeEventBus;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentEnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentPortAllocationRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.sftp.SftpGatewayRegistry;
import online.yudream.base.plugin.mcpanel.infrastructure.sftp.SftpGatewayServer;
import online.yudream.base.plugin.mcpanel.infrastructure.sftp.SftpHostKeyStore;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import online.yudream.base.plugin.mcpanel.interfaces.controller.McpanelAdminController;
import online.yudream.base.plugin.mcpanel.interfaces.controller.McpanelAdminController2;
import online.yudream.base.plugin.mcpanel.interfaces.controller.McpanelNodeOpsController;
import online.yudream.base.plugin.mcpanel.interfaces.http.NodeOpsFacade;
import online.yudream.base.plugin.mcpanel.interfaces.controller.McpanelCoreController;
import online.yudream.base.plugin.mcpanel.interfaces.controller.McpanelDockerImageController;
import online.yudream.base.plugin.mcpanel.interfaces.controller.McpanelInstanceController;
import online.yudream.base.plugin.mcpanel.interfaces.controller.McpanelOpsController;
import online.yudream.base.plugin.mcpanel.interfaces.controller.McpanelP2pController;
import online.yudream.base.plugin.mcpanel.interfaces.http.McpanelHttpFacade;
import online.yudream.base.plugin.mcpanel.interfaces.http.McpanelInstanceFacade;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.annotation.PluginDashboardCard;
import online.yudream.base.plugin.spi.annotation.PluginFrontend;
import online.yudream.base.plugin.spi.annotation.PluginPermission;
import online.yudream.base.plugin.spi.annotation.PluginPermissions;
import online.yudream.base.plugin.spi.annotation.PluginRoute;
import online.yudream.base.plugin.spi.annotation.PluginSpec;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.core.YuDreamPlugin;
import online.yudream.base.plugin.spi.system.FrameworkServices;

import java.io.IOException;
import java.util.List;

/**
 * MC 面板插件入口。入口只做装配、注册与生命周期；业务在 application，传输在 infrastructure。
 * M2/M3：实例 CRUD/生命周期/控制台/文件/备份 + 模板 + 设置 + 租户范围 + 贡献 + P2P 信令骨架。
 */
@PluginSpec(
        code = McpanelPlugin.CODE,
        name = "mcpanel",
        version = McpanelPlugin.VERSION,
        description = "仿 MCSManager 的 Minecraft 服务器面板。提供节点管理、一次性注册、面板—节点控制信道、实例全生命周期（创建/启停/控制台）、文件与备份管理、服务端模板、整合包导入、租户范围与节点贡献申请。"
)
@PluginDashboardCard(
        code = "mcpanel.overview",
        title = "MC 面板 · 数据监控",
        description = "节点在线、实例运行状态与资源总览",
        icon = "i-ri:dashboard-line",
        category = "MC 面板",
        permission = McpanelPlugin.VIEW_PERMISSION,
        component = "mcpanel/DashboardOverviewCard",
        actionPath = "/platform/plugins/mcpanel/admin/overview",
        tone = "blue",
        defaultW = 4,
        defaultH = 3,
        minW = 3,
        minH = 2,
        sort = 80,
        defaultOnFirstVisit = true
)
@PluginDashboardCard(
        code = "mcpanel.instances",
        title = "MC 面板 · 应用实例",
        description = "实例卡片列表与节点筛选",
        icon = "i-ri:gamepad-line",
        category = "MC 面板",
        permission = McpanelPlugin.VIEW_PERMISSION,
        component = "mcpanel/DashboardOverviewCard",
        actionPath = "/platform/plugins/mcpanel/admin/instances",
        tone = "green",
        defaultW = 4,
        defaultH = 2,
        minW = 3,
        minH = 2,
        sort = 81
)
@PluginDashboardCard(
        code = "mcpanel.market",
        title = "MC 面板 · 应用市场",
        description = "模板与核心包一键创建 / ZIP 导入",
        icon = "i-ri:shopping-bag-line",
        category = "MC 面板",
        permission = McpanelPlugin.VIEW_PERMISSION,
        component = "mcpanel/DashboardQuickLinkCard",
        actionPath = "/platform/plugins/mcpanel/admin/market",
        tone = "purple",
        defaultW = 4,
        defaultH = 2,
        minW = 3,
        minH = 2,
        sort = 82
)
@PluginDashboardCard(
        code = "mcpanel.quickstart",
        title = "MC 面板 · 快速开始",
        description = "向导包安装服务器（对标 MCSM QuickStart）",
        icon = "i-ri:rocket-2-line",
        category = "MC 面板",
        permission = McpanelPlugin.MANAGE_PERMISSION,
        component = "mcpanel/DashboardQuickLinkCard",
        actionPath = "/platform/plugins/mcpanel/admin/quickstart",
        tone = "orange",
        defaultW = 4,
        defaultH = 2,
        minW = 3,
        minH = 2,
        sort = 83
)
@PluginPermissions({
        @PluginPermission(code = McpanelPlugin.VIEW_PERMISSION, name = "查看面板节点与实例", module = "平台插件",
                description = "查看节点/实例列表、详情、状态、资源流与控制台输出"),
        @PluginPermission(code = McpanelPlugin.USE_PERMISSION, name = "操作面板实例", module = "平台插件",
                description = "实例启停/重启/强杀与控制台输入"),
        @PluginPermission(code = McpanelPlugin.MANAGE_PERMISSION, name = "管理面板节点与实例", module = "平台插件",
                description = "节点与实例创建/编辑、端点与证书 pin 配置、文件写操作、模板、备份、设置、注册令牌签发"),
        @PluginPermission(code = McpanelPlugin.DELETE_PERMISSION, name = "删除面板节点与实例", module = "平台插件",
                description = "删除节点/实例/模板（危险操作）")
})
@PluginFrontend(
        moduleName = "mcpanel",
        menuTitle = "MC 面板",
        menuIcon = "i-ri:remote-control-line",
        menuSort = 55,
        styles = {"style.css"},
        routes = {
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/nodes", name = "platform-plugin-mcpanel-nodes",
                        title = "节点管理", icon = "i-ri:server-line", component = "mcpanel/Nodes",
                        permission = McpanelPlugin.VIEW_PERMISSION, sort = 5),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/node-deploy", name = "platform-plugin-mcpanel-node-deploy",
                        title = "节点部署指南", icon = "i-ri:book-open-line", component = "mcpanel/NodeDeploy",
                        permission = McpanelPlugin.VIEW_PERMISSION, sort = 6),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/overview", name = "platform-plugin-mcpanel-overview",
                        title = "数据监控", icon = "i-ri:dashboard-line", component = "mcpanel/Overview",
                        permission = McpanelPlugin.VIEW_PERMISSION, sort = 8),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/nodes/:id", name = "platform-plugin-mcpanel-node-detail",
                        title = "节点详情", icon = "i-ri:pulse-line", component = "mcpanel/NodeDetail",
                        permission = McpanelPlugin.VIEW_PERMISSION, hideInMenu = true, sort = 20),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/nodes/:id/terminal", name = "platform-plugin-mcpanel-node-terminal",
                        title = "节点终端", icon = "i-ri:terminal-line", component = "mcpanel/NodeTerminal",
                        permission = McpanelPlugin.USE_PERMISSION, hideInMenu = true, sort = 21),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/nodes/:id/files", name = "platform-plugin-mcpanel-node-files",
                        title = "节点文件", icon = "i-ri:folder-line", component = "mcpanel/NodeFiles",
                        permission = McpanelPlugin.MANAGE_PERMISSION, hideInMenu = true, sort = 22),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/quickstart", name = "platform-plugin-mcpanel-quickstart",
                        title = "快速开始", icon = "i-ri:rocket-2-line", component = "mcpanel/QuickStart",
                        permission = McpanelPlugin.MANAGE_PERMISSION, sort = 26),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/market", name = "platform-plugin-mcpanel-market",
                        title = "应用市场", icon = "i-ri:shopping-bag-line", component = "mcpanel/Market",
                        permission = McpanelPlugin.VIEW_PERMISSION, sort = 28),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances/:id/settings", name = "platform-plugin-mcpanel-instance-settings",
                        title = "实例设置", icon = "i-ri:settings-4-line", component = "mcpanel/InstanceSettings",
                        permission = McpanelPlugin.VIEW_PERMISSION, hideInMenu = true, sort = 45),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/audit", name = "platform-plugin-mcpanel-audit",
                        title = "操作审计", icon = "i-ri:shield-check-line", component = "mcpanel/Audit",
                        permission = McpanelPlugin.VIEW_PERMISSION, sort = 62),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances", name = "platform-plugin-mcpanel-instances",
                        title = "实例管理", icon = "i-ri:gamepad-line", component = "mcpanel/Instances",
                        permission = McpanelPlugin.VIEW_PERMISSION, sort = 30),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/trash", name = "platform-plugin-mcpanel-trash",
                        title = "回收站", icon = "i-ri:delete-bin-line", component = "mcpanel/Trash",
                        permission = McpanelPlugin.VIEW_PERMISSION, sort = 32),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances/create", name = "platform-plugin-mcpanel-instance-create",
                        title = "创建实例", icon = "i-ri:magic-line", component = "mcpanel/CreateInstance",
                        permission = McpanelPlugin.MANAGE_PERMISSION, hideInMenu = true, sort = 35),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances/:id", name = "platform-plugin-mcpanel-instance-detail",
                        title = "实例详情", icon = "i-ri:terminal-line", component = "mcpanel/InstanceDetail",
                        permission = McpanelPlugin.VIEW_PERMISSION, hideInMenu = true, sort = 40),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances/:id/files", name = "platform-plugin-mcpanel-instance-files",
                        title = "实例文件", icon = "i-ri:folder-line", component = "mcpanel/InstanceFiles",
                        permission = McpanelPlugin.VIEW_PERMISSION, hideInMenu = true, sort = 41),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances/:id/schedules", name = "platform-plugin-mcpanel-instance-schedules",
                        title = "计划任务", icon = "i-ri:time-line", component = "mcpanel/InstanceSchedules",
                        permission = McpanelPlugin.VIEW_PERMISSION, hideInMenu = true, sort = 42),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances/:id/mods", name = "platform-plugin-mcpanel-instance-mods",
                        title = "模组插件", icon = "i-ri:puzzle-line", component = "mcpanel/InstanceMods",
                        permission = McpanelPlugin.VIEW_PERMISSION, hideInMenu = true, sort = 44),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances/:id/proxy", name = "platform-plugin-mcpanel-instance-proxy",
                        title = "代理与子服", icon = "i-ri:git-branch-line", component = "mcpanel/InstanceProxy",
                        permission = McpanelPlugin.VIEW_PERMISSION, hideInMenu = true, sort = 46),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances/:id/domain", name = "platform-plugin-mcpanel-instance-domain",
                        title = "实例域名", icon = "i-ri:global-line", component = "mcpanel/InstanceDomain",
                        permission = McpanelPlugin.VIEW_PERMISSION, hideInMenu = true, sort = 47),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/instances/:id/server-config", name = "platform-plugin-mcpanel-instance-server-config",
                        title = "服务端配置", icon = "i-ri:settings-4-line", component = "mcpanel/ServerConfig",
                        permission = McpanelPlugin.VIEW_PERMISSION, hideInMenu = true, sort = 43),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/docker-images", name = "platform-plugin-mcpanel-docker-images",
                        title = "Docker 镜像", icon = "i-ri:box-3-line", component = "mcpanel/DockerImages",
                        permission = McpanelPlugin.VIEW_PERMISSION, sort = 48),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/templates", name = "platform-plugin-mcpanel-templates",
                        title = "服务端模板", icon = "i-ri:stack-line", component = "mcpanel/Templates",
                        permission = McpanelPlugin.VIEW_PERMISSION, sort = 50),
                @PluginRoute(path = "/platform/plugins/mcpanel/admin/settings", name = "platform-plugin-mcpanel-settings",
                        title = "面板设置", icon = "i-ri:settings-line", component = "mcpanel/Settings",
                        permission = McpanelPlugin.MANAGE_PERMISSION, sort = 60)
        }
)
public class McpanelPlugin implements YuDreamPlugin {

    public static final String CODE = "mcpanel";
    public static final String VERSION = "0.19.14";

    public static final String VIEW_PERMISSION = "plugin:mcpanel:view";
    public static final String USE_PERMISSION = "plugin:mcpanel:use";
    public static final String MANAGE_PERMISSION = "plugin:mcpanel:manage";
    public static final String DELETE_PERMISSION = "plugin:mcpanel:delete";

    /**
     * 宿主备份中心端口（SPI 2.33.0）：触发 server-data 范围备份（targetCode 空=本机导出）
     * 与按范围查任务摘要（实例页「本地/异地」标识合并列表用）。
     * 宿主 SPI 过旧（无备份契约）时返回 null：备份中心动作报「无通道」，本机档不受影响。
     */
    private static online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService.BackupCenter buildBackupCenter(
            online.yudream.base.plugin.spi.core.PluginContext context) {
        try {
            final online.yudream.base.plugin.spi.system.backup.PluginBackupOperations operations =
                    context.framework().backups("mcpanel");
            return new online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService.BackupCenter() {
                @Override
                public String trigger(String instanceId, String targetCode) {
                    return operations.startScopeBackup(
                            new online.yudream.base.plugin.spi.system.backup.PluginScopeBackupRequest(
                                    "server-data", targetCode, java.util.Map.of("instanceId", instanceId)));
                }

                @Override
                public java.util.List<java.util.Map<String, Object>> jobs(String scopeCode, int limit) {
                    return operations.listScopeJobs(scopeCode, limit).stream()
                            .map(job -> {
                                java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                                row.put("jobId", job.jobId());
                                row.put("status", job.status());
                                row.put("percent", job.percent());
                                row.put("message", job.message());
                                row.put("archiveName", job.archiveName());
                                row.put("targetCode", job.targetCode());
                                row.put("targetName", job.targetName());
                                row.put("createdAt", job.createdAt());
                                row.put("instanceId", job.options() == null
                                        ? "" : String.valueOf(job.options().get("instanceId")));
                                return row;
                            })
                            .toList();
                }
            };
        } catch (LinkageError ignored) {
            return null;
        }
    }

    /**
     * 注册 P2P 直连扩展点；ymcl-adapter 缺失时按软依赖降级（LinkageError 兜底，
     * 扩展点接口类来自适配器 JAR，未安装即不可达）。
     */
    private void registerYmclP2p(online.yudream.base.plugin.spi.core.PluginContext context,
                                 online.yudream.base.plugin.mcpanel.application.service.P2PSessionService p2pSessionService,
                                 DomainService domainService) {
        try {
            context.registerExtension(
                    online.yudream.base.plugin.ymcl.api.YmclP2pProvider.class,
                    new online.yudream.base.plugin.mcpanel.application.service.YmclP2pBridge(
                            p2pSessionService, domainService));
        } catch (LinkageError ignored) {
            // ymcl-adapter 未安装，降级即可（面板自身的 P2P 管理端不受影响）
        }
    }

    private volatile NodeConnectionManager connectionManager;
    private volatile NodeEventBus eventBus;
    private volatile Thread restorerThread;

    @Override
    public void onEnable(PluginContext context) {
        NodeConnectionManager manager = null;
        NodeEventBus bus = null;
        try {
            ObjectMapper mapper = online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson.mapper();
            McpanelNodeRepository nodeRepository = new DocumentNodeRepository(context.documents(), mapper);
            EnrollTokenRepository tokenRepository = new DocumentEnrollTokenRepository(context.documents(), mapper);
            final McpanelInstanceRepository instanceRepository = new DocumentMcpanelInstanceRepository(context.documents(), mapper);
            PortAllocationRepository portRepository = new DocumentPortAllocationRepository(context.documents(), mapper);
            NodeSecrets secrets = new NodeSecrets(context.secrets());
            // 宿主备份中心扩展点：面板元数据与节点密钥纳入全量备份/异地备份（宿主 SPI 无此契约时按软依赖降级）
            try {
                context.registerExtension(
                        online.yudream.base.plugin.spi.system.backup.PluginBackupProvider.class,
                        new online.yudream.base.plugin.mcpanel.infrastructure.backup.McpanelPanelDataBackupProvider(
                                context.documents(), context.secrets(), mapper));
            } catch (LinkageError ignored) {
                // 宿主 SPI 过旧无备份扩展点，跳过注册即可
            }
            bus = new NodeEventBus();
            manager = new NodeConnectionManager(nodeRepository, secrets, bus);
            this.eventBus = bus;
            this.connectionManager = manager;
            final NodeConnectionManager finalManager = manager;
            final NodeEventBus finalBus = bus;

            final FrameworkServices framework = context.framework();
            final MinecraftLinkService link = new MinecraftLinkService(
                    () -> context.service(MinecraftLinkService.PROVIDER_CODE,
                            online.yudream.base.plugin.minecraft.api.PluginMinecraftService.class));
            final online.yudream.base.plugin.mcpanel.application.service.AuthlibLinkService authlibLink =
                    new online.yudream.base.plugin.mcpanel.application.service.AuthlibLinkService(
                            () -> context.service(
                                    online.yudream.base.plugin.mcpanel.application.service.AuthlibLinkService.PROVIDER_CODE,
                                    online.yudream.base.plugin.authlib.api.PluginAuthlibInfo.class));
            SettingsService settingsService = new SettingsService(context.documents(), mapper, secrets,
                    authlibLink::apiRoot);
            online.yudream.base.plugin.mcpanel.application.service.ArtifactStoreService artifactStoreService =
                    new online.yudream.base.plugin.mcpanel.application.service.ArtifactStoreService(context.files());
            final McpanelEnrollService enrollService = new McpanelEnrollService(nodeRepository, tokenRepository, secrets);
            // 审计 recorder 提前具名：实例服务与 NodeOpsFacade 共享同一窄接口；
            // tenantId null 收敛为 ""（文档存储不接受 null，平台直属以空串表达）。
            final McpanelInstanceAppService.AuditRecorder auditRecorder =
                    (actor, action, targetType, targetId, detail, tenantId) -> {
                        // 审计日志写文档集合（M2 最小实现：固定字段）。
                        var audit = new java.util.LinkedHashMap<String, Object>();
                        audit.put("actor", actor);
                        audit.put("action", action);
                        audit.put("targetType", targetType);
                        audit.put("targetId", targetId);
                        audit.put("detail", detail == null ? "" : detail);
                        audit.put("tenantId", tenantId == null ? "" : tenantId);
                        audit.put("at", System.currentTimeMillis());
                        context.documents().save("mcpanel_audit_logs",
                                actor + ":" + action + ":" + targetId + ":" + System.nanoTime(), audit);
                    };
            // gateway 必须显式实现 4 参 call 透传超时：接口默认实现会把超时丢弃并
            // 回落 30s，create 的 15 分钟长超时（内联拉镜像）会被钳成必然超时。
            // 实例备份保留策略（按实例文档存储）+ 宿主备份中心端口（SPI 过旧时降级 null）。
            final online.yudream.base.plugin.mcpanel.application.service.BackupPolicyStore backupPolicyStore =
                    new online.yudream.base.plugin.mcpanel.application.service.BackupPolicyStore(context.documents());
            final online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService.BackupCenter backupCenter =
                    buildBackupCenter(context);
            final McpanelInstanceAppService instanceService = new McpanelInstanceAppService(
                    instanceRepository, nodeRepository, portRepository,
                    new McpanelInstanceAppService.NodeCallGateway() {
                        @Override
                        public java.util.concurrent.CompletableFuture<java.util.Map<String, Object>> call(
                                String nodeId, String method, java.util.Map<String, Object> payload) {
                            return finalManager.call(nodeId, method, payload);
                        }

                        @Override
                        public java.util.concurrent.CompletableFuture<java.util.Map<String, Object>> call(
                                String nodeId, String method, java.util.Map<String, Object> payload, long timeoutMs) {
                            return finalManager.call(nodeId, method, payload, timeoutMs);
                        }
                    },
                    new PortAllocator(portRepository),
                    auditRecorder,
                    new McpanelInstanceAppService.TenancyScope() {
                        @Override
                        public boolean canAccess(String scopeKey, online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance instance) {
                            // M7 实体未上线：全部放行（manage 端点权限 + 单一面板部署边界）。
                            return true;
                        }

                        @Override
                        public String tenantOf(Object requestContext, online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode node) {
                            // M7 实体未上线：实例一律平台直属（tenantId=null）。
                            return null;
                        }
                    },
                    link,
                    backupCenter);
            instanceService.attachBackupPolicyStore(backupPolicyStore);
            // 事件触发型任务（对标 MCSM eventTask）：意外退出自动重启 + 节点上线自动启动。
            // 与实例服务共用同一节点调用通道与审计；触发 RPC 在服务自有单线程内串行执行。
            final online.yudream.base.plugin.mcpanel.application.service.InstanceEventTaskService eventTaskService =
                    new online.yudream.base.plugin.mcpanel.application.service.InstanceEventTaskService(
                            instanceRepository,
                            finalManager::call,
                            auditRecorder);
            instanceService.attachEventTasks(eventTaskService);
            // SFTP 单端口网关：别名注册表 + host key 持久化；实例服务用网关四件套
            // 替换节点直连凭据，设置热更经 SettingsService.onSaved 回调启停。
            SftpGatewayRegistry sftpRegistry = new SftpGatewayRegistry();
            SftpGatewayServer sftpGatewayServer = new SftpGatewayServer(sftpRegistry,
                    new SftpHostKeyStore(context.files()));
            instanceService.attachSftpGateway(new McpanelInstanceAppService.SftpGatewayHandle(
                    sftpRegistry, settingsService::sftpGatewayView));
            settingsService.setOnSaved(() -> {
                try {
                    applySftpGateway(sftpGatewayServer, settingsService.sftpGatewayView());
                } catch (IOException | RuntimeException error) {
                    System.err.println("[mcpanel] SFTP 网关应用新设置失败: " + error.getMessage());
                }
            });
            context.onDispose(sftpGatewayServer::close);
            try {
                applySftpGateway(sftpGatewayServer, settingsService.sftpGatewayView());
            } catch (IOException error) {
                // 网关起不来（端口占用等）不阻断插件其余功能；保存设置可再次触发。
                System.err.println("[mcpanel] SFTP 网关启动失败: " + error.getMessage());
            }
            // 节点删除时先级联清理其下实例（容器/端口/记录/回传），再删节点记录。
            McpanelNodeAppService nodeService = new McpanelNodeAppService(nodeRepository, tokenRepository,
                    secrets, manager, System::currentTimeMillis, instanceService::cascadeDeleteByNode);
            TenantScopeService tenants = new TenantScopeService(settingsService,
                    new TenantScopeService.MembershipResolver() {
                        @Override
                        public List<String> departmentIdsOf(long userId) {
                            try {
                                return framework.users().listDepartments(userId).stream()
                                        .<String>map(dept -> String.valueOf(dept.id()))
                                        .toList();
                            } catch (RuntimeException error) {
                                return List.of();
                            }
                        }

                        @Override
                        public List<String> roleIdsOf(long userId) {
                            try {
                                return framework.users().listRoles(userId).stream()
                                        .<String>map(role -> String.valueOf(role.id()))
                                        .toList();
                            } catch (RuntimeException error) {
                                return List.of();
                            }
                        }
                    });
            // 租户实体集合由设置快照刷新（M7 实体未上线时为空集合 = 全平台直属）。
            TenantScopeService.TenantStore.replace(List.of());
            TenantScopeService.UsageStore.bind(tenantId -> {
                long instances = instanceRepository.findAll().stream()
                        .filter(i -> tenantId.equals(i.tenantId()) && "running".equalsIgnoreCase(i.state()))
                        .count();
                long cpu = instanceRepository.findAll().stream()
                        .filter(i -> tenantId.equals(i.tenantId()) && "running".equalsIgnoreCase(i.state()))
                        .mapToLong(McpanelAggregateProxy::cpuOf).sum();
                long mem = instanceRepository.findAll().stream()
                        .filter(i -> tenantId.equals(i.tenantId()) && "running".equalsIgnoreCase(i.state()))
                        .mapToLong(McpanelAggregateProxy::memOf).sum();
                return new TenantScopeService.QuotaUsage(instances, cpu, mem);
            });
            TemplateService templateService = new TemplateService(context.documents(), mapper);
            online.yudream.base.plugin.mcpanel.application.service.DockerImageService dockerImageService =
                    new online.yudream.base.plugin.mcpanel.application.service.DockerImageService(context.documents());
            dockerImageService.seedDefaultsIfEmpty();
            online.yudream.base.plugin.mcpanel.application.service.CoreDownloadService coreDownloadService =
                    new online.yudream.base.plugin.mcpanel.application.service.CoreDownloadService(
                            () -> {
                                try {
                                    Object value = settingsService.view();
                                    if (value instanceof java.util.Map<?, ?> map) {
                                        Object download = map.get("coreDownload");
                                        if (download instanceof java.util.Map<?, ?> nested) {
                                            Object base = nested.get("fastMirrorBase");
                                            if (base != null && !String.valueOf(base).isBlank()) {
                                                return String.valueOf(base);
                                            }
                                        }
                                    }
                                } catch (RuntimeException ignored) {
                                }
                                return "https://download.fastmirror.net";
                            });
            ContributionService contributionService = new ContributionService(context.documents(), mapper, settingsService);
            // 实例域名自动解析：驱动（Cloudflare/阿里云/腾讯云）在 infrastructure.dns，
            // 实例读写经应用服务回灌（创建时分配、删除时释放）。
            DomainService domainService = new DomainService(settingsService, new DomainService.InstancePort() {
                @Override
                public java.util.List<online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance> all() {
                    return instanceService.allInstances();
                }

                @Override
                public void setDomain(String instanceId, String slug, boolean enabled) {
                    instanceService.setInstanceDomain(instanceId, slug, enabled);
                }
            }, auditRecorder);
            instanceService.attachDomains(domainService);
            // 单端口入口（mc-router）：只在下述实例真正使用入口模式时才推送/对账（其余模式零外呼）。
            var entryRouteService = new online.yudream.base.plugin.mcpanel.application.service.EntryRouteService(
                    new online.yudream.base.plugin.mcpanel.application.service.EntryRouteService.InstanceSource() {
                        @Override
                        public java.util.List<online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance> all() {
                            return instanceService.allInstances();
                        }

                        @Override
                        public String directHostOf(online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode node) {
                            return instanceService.directHostOf(node);
                        }
                    },
                    nodeRepository,
                    () -> settingsService.load().entry(),
                    domainService::suffix);
            instanceService.attachEntryConfig(() -> settingsService.load().entry());
            instanceService.attachEntryRoutes(entryRouteService);
            context.onDispose(entryRouteService::close);
            ModpackService modpackService = new ModpackService(settingsService, VERSION);
            // 整合包分片上传暂存（实例创建场景）：大包不再整份 multipart 进宿主，避免请求超时。
            var modpackUploadService = new online.yudream.base.plugin.mcpanel.application.service.ModpackUploadService(
                    modpackService::inspect);
            context.onDispose(modpackUploadService::close);

            context.registerHttpController(new McpanelAdminController(
                    new McpanelHttpFacade(nodeService, enrollService, framework.security())));
            var playersService = new online.yudream.base.plugin.mcpanel.application.service.InstancePlayersService(
                    instanceService, nodeRepository);
            // 核心安装进度跟踪（轮询节点 task.get）+ 性能历史环形窗口（stats 采样落文档）。
            var installTracker = new online.yudream.base.plugin.mcpanel.application.service.InstallTaskTracker(
                    (nodeId, method, payload) -> {
                        try {
                            return finalManager.call(nodeId, method, payload).join();
                        }
                        catch (RuntimeException error) {
                            // 保留原始异常类型（NodeCallException 错误码供跟踪器判任务丢失），只解 CompletionException 包装。
                            if (error instanceof java.util.concurrent.CompletionException
                                    && error.getCause() instanceof RuntimeException cause) {
                                throw cause;
                            }
                            throw error;
                        }
                    },
                    auditRecorder);
            var metricsHistory = new online.yudream.base.plugin.mcpanel.application.service.MetricsHistoryService(
                    context.documents());
            // 安装计划存根：重试安装的重建来源（install.run 受理时按实例覆写）。
            var installPlanStore = new online.yudream.base.plugin.mcpanel.application.service.InstallPlanStore(
                    context.documents());
            instanceService.attachInstallTracker(installTracker);
            instanceService.attachInstallPlanStore(installPlanStore);
            // MR/CF 换源：安装计划条目统一补全源回退候选（镜像优先、官方兜底）。
            instanceService.attachPlanSourceEnricher(modpackService::enrichPlanItem);
            instanceService.attachMetricsHistory(metricsHistory);
            // 节点列表 sparkline 也读同一份后台采集历史（节点管理页不再页面内攒点）。
            nodeService.attachMetricsHistory(metricsHistory);
            context.onDispose(installTracker::close);
            context.onDispose(metricsHistory::close);
            // 代理纳管：文件网关请求期才经 instanceService 走节点（此时必然已装配）。
            var proxyGroupService = new online.yudream.base.plugin.mcpanel.application.service.ProxyGroupService(
                    context.documents(), instanceRepository, nodeRepository,
                    (scopeKey, instanceId, method, args) -> instanceService.files(scopeKey, instanceId, method, args),
                    auditRecorder);
            // 子服软链接同步（Tier A：zip → 面板中转 → 解压覆盖，全部复用现有节点通道）。
            var syncLinkService = new online.yudream.base.plugin.mcpanel.application.service.SyncLinkService(
                    context.documents(), instanceRepository, nodeRepository,
                    (scopeKey, instanceId, method, args) -> instanceService.files(scopeKey, instanceId, method, args),
                    (actor, scopeKey, instanceId, name, bytes) ->
                            instanceService.upload(actor, instanceId, name, bytes, null),
                    auditRecorder);
            instanceService.attachSidecars(syncLinkService, proxyGroupService);
            // 回收站：删除实例（未勾选永久删除）且节点确认目录已进 data/trash/ 后落快照。
            var trashService = new online.yudream.base.plugin.mcpanel.application.service.McpanelTrashAppService(
                    new online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentTrashRepository(
                            context.documents(), mapper),
                    nodeRepository,
                    instanceService,
                    (nodeId, method, payload) -> finalManager.call(nodeId, method, payload),
                    auditRecorder,
                    mapper);
            instanceService.attachTrashSink(trashService::record);
            context.registerHttpController(new online.yudream.base.plugin.mcpanel.interfaces.controller.McpanelTrashController(
                    trashService, framework.security()));
            // 实例状态展示口径唯一来源：stats 快照优先、DB 回退（列表/详情/总览共用）。
            var stateResolver = new online.yudream.base.plugin.mcpanel.application.service.InstanceStateResolver(
                    nodeRepository);
            final McpanelInstanceFacade instanceFacade = new McpanelInstanceFacade(instanceService,
                    framework.security(), templateService, dockerImageService, playersService,
                    // 请求期才执行：eventBus 字段此时必然就绪（onEnable 已完成装配）。
                    new McpanelInstanceFacade.OutputEventsOpener() {
                        @Override
                        public PluginSseStream open(String nodeId, String instanceId) {
                            NodeEventBus eventBusRef = eventBus;
                            if (eventBusRef == null) {
                                throw new IllegalStateException("节点事件总线未就绪");
                            }
                            return eventBusRef.openFiltered(nodeId, "instance.output", "instanceId", instanceId);
                        }

                        @Override
                        public PluginSseStream openTopic(String nodeId, String eventType, String matchKey, String matchValue) {
                            NodeEventBus eventBusRef = eventBus;
                            if (eventBusRef == null) {
                                throw new IllegalStateException("节点事件总线未就绪");
                            }
                            return eventBusRef.openFiltered(nodeId, eventType, matchKey, matchValue);
                        }
                    }, proxyGroupService, installTracker, stateResolver);
            // 事件任务自动启动成功后同样重挂输出泵（实例停止时节点回收泵）。
            eventTaskService.setOutputReattachListener(instanceFacade::reattachOutput);
            context.registerHttpController(new McpanelInstanceController(instanceFacade));
            context.registerHttpController(new McpanelAdminController2(
                    templateService, settingsService, contributionService, artifactStoreService,
                    link, framework.security()));
            var nodeOpsFacade = new online.yudream.base.plugin.mcpanel.interfaces.http.NodeOpsFacade(
                    nodeRepository, manager,
                    (n, eventType, matchKey, matchValue) -> finalBus.openFiltered(n, eventType, matchKey, matchValue),
                    auditRecorder);
            context.registerHttpController(new McpanelNodeOpsController(nodeOpsFacade, framework.security()));
            context.registerHttpController(new McpanelDockerImageController(
                    dockerImageService, framework.security()));
            context.registerHttpController(new McpanelCoreController(
                    coreDownloadService, framework.security()));
            // 启动器 P2P 会话（M6）：面板即 rendezvous——校验/票据/候选中继；玩家流量不经面板。
            var p2pSessionService = new online.yudream.base.plugin.mcpanel.application.service.P2PSessionService(
                    instanceRepository,
                    () -> settingsService.load().p2p(),
                    new online.yudream.base.plugin.mcpanel.application.service.P2PSessionService.NodePort() {
                        @Override
                        public boolean supports(String nodeId, String capability) {
                            return instanceService.nodeSupports(nodeId, capability);
                        }

                        @Override
                        public java.util.Map<String, Object> call(String nodeId, String method,
                                                                   java.util.Map<String, Object> payload) {
                            return instanceService.invokeNode(nodeId, method, payload);
                        }

                        @Override
                        public String advertisedHost(String nodeId) {
                            return instanceService.nodeAdvertisedHost(nodeId);
                        }
                    },
                    auditRecorder);
            context.onDispose(p2pSessionService::close);
            context.registerHttpController(new McpanelP2pController(p2pSessionService, framework.security()));
            // 向启动器适配器贡献 P2P 直连能力（YAP §6.12 扩展点）：适配器只聚合、不依赖面板，
            // 因此不会与「business → adapter」的贡献方向成环（宿主会拒绝环上的可选依赖）。
            registerYmclP2p(context, p2pSessionService, domainService);
            // 宿主备份中心 server-data 范围：实例世界数据整包入档/回灌
            // （节点需支持分块备份通道；旧节点在导出时按 caps 自动跳过并告警）
            try {
                context.registerExtension(
                        online.yudream.base.plugin.spi.system.backup.PluginBackupProvider.class,
                        new online.yudream.base.plugin.mcpanel.infrastructure.backup.McpanelServerDataBackupProvider(
                                instanceRepository, manager, mapper,
                                // 打包时下发实例保留策略：节点生成整包后立即清理超额/过期旧档
                                instanceId -> {
                                    var policy = backupPolicyStore.get(instanceId);
                                    return new int[] {policy.keepCount(), policy.keepDays()};
                                }));
            } catch (LinkageError ignored) {
                // 宿主 SPI 过旧无备份扩展点，跳过注册即可
            }

            var overviewService = new online.yudream.base.plugin.mcpanel.application.service.OverviewService(
                    stateResolver, nodeRepository, instanceRepository, metricsHistory);
            // authlib 注入（实例粒度）：注入源读面板设置，开关按实例追加/移除 -javaagent。
            var authlibInjectionService = new online.yudream.base.plugin.mcpanel.application.service.AuthlibInjectionService(
                    instanceRepository, settingsService, artifactStoreService, authlibLink,
                    new online.yudream.base.plugin.mcpanel.application.service.AuthlibInjectionService.InstanceMutations() {
                        @Override
                        public void upload(String scopeKey, String instanceId, String path, byte[] data, String sha256Hex) {
                            instanceService.upload(scopeKey, instanceId, path, data, sha256Hex);
                        }

                        @Override
                        public java.util.Map<String, Object> update(String actor, String scopeKey,
                                                                    String instanceId,
                                                                    online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance spec) {
                            return instanceService.update(actor, scopeKey, instanceId, spec);
                        }
                    },
                    auditRecorder);
            // 在线时长注入（实例粒度）：按制品矩阵匹配插件/模组制品放入实例目录。
            var playtimeInjectionService = new online.yudream.base.plugin.mcpanel.application.service.PlaytimeInjectionService(
                    instanceRepository, settingsService, artifactStoreService,
                    new online.yudream.base.plugin.mcpanel.application.service.PlaytimeInjectionService.InstanceFileOps() {
                        @Override
                        public void upload(String scopeKey, String instanceId, String path, byte[] data, String sha256Hex) {
                            instanceService.upload(scopeKey, instanceId, path, data, sha256Hex);
                        }

                        @Override
                        public void delete(String scopeKey, String instanceId, String path) {
                            instanceService.files(scopeKey, instanceId, "delete", java.util.Map.of("path", path));
                        }

                        @Override
                        public java.util.List<String> listNames(String scopeKey, String instanceId, String dir) {
                            var result = instanceService.files(scopeKey, instanceId, "list",
                                    java.util.Map.of("path", dir, "page", 1, "size", 200));
                            Object entries = result.get("entries");
                            if (!(entries instanceof java.util.List<?> list)) {
                                return java.util.List.of();
                            }
                            return list.stream()
                                    .filter(item -> item instanceof java.util.Map<?, ?> map
                                            && Boolean.TRUE.equals(map.get("isDir")) == false)
                                    .map(item -> String.valueOf(((java.util.Map<?, ?>) item).get("name")))
                                    .toList();
                        }
                    },
                    auditRecorder);
            // 实例计划任务的备份触发端口：与实例页手动备份共用同一 BackupCenter
            // （宿主 SPI 过旧时为 null，备份动作执行时报「无通道」而非影响其他任务类型）。
            final online.yudream.base.plugin.mcpanel.application.service.ScheduleService.BackupTrigger backupTrigger =
                    backupCenter == null ? null : backupCenter::trigger;
            var scheduleService = new online.yudream.base.plugin.mcpanel.application.service.ScheduleService(
                    context.documents(),
                    (instanceId, command) -> instanceService.command("system", "user:system", instanceId, command),
                    backupTrigger);
            var serverConfigService = new online.yudream.base.plugin.mcpanel.application.service.ServerConfigService(
                    new online.yudream.base.plugin.mcpanel.application.service.ServerConfigService.InstanceFiles() {
                        @Override
                        public java.util.Map<String, Object> read(String scopeKey, String instanceId, java.util.Map<String, Object> args) {
                            return instanceService.files(scopeKey, instanceId, "read", args);
                        }

                        @Override
                        public java.util.Map<String, Object> write(String scopeKey, String instanceId, java.util.Map<String, Object> args) {
                            return instanceService.files(scopeKey, instanceId, "write", args);
                        }
                    });
            // PROXY protocol 跟随接入方式：入口模式自动开、其它模式自动关（实例被直连时不能被 PROXY 头拒掉）。
            var proxyProtocolSync = new online.yudream.base.plugin.mcpanel.application.service.ProxyProtocolSyncService(
                    nodeId -> instanceService.allInstances().stream()
                            .filter(item -> nodeId.equals(item.nodeId())).toList(),
                    serverConfigService,
                    auditRecorder);
            instanceService.attachProxyProtocolSync(proxyProtocolSync);
            nodeService.attachAccessModeListener((nodeId, accessMode) ->
                    proxyProtocolSync.syncForNode("system", nodeId, accessMode));
            scheduleService.start();
            var quickStartService = new online.yudream.base.plugin.mcpanel.application.service.QuickStartPackageService(
                    context.documents());
            quickStartService.seedDefaultsIfEmpty();
            // 大文件分片上传任务：浏览器分片直传面板，面板经 uploadOp 流式中转节点。
            var uploadTaskService = new online.yudream.base.plugin.mcpanel.application.service.UploadTaskService(
                    instanceService::uploadOp);
            context.registerHttpController(new McpanelOpsController(
                    overviewService, scheduleService, serverConfigService, instanceService,
                    new online.yudream.base.plugin.mcpanel.application.service.ModrinthService(settingsService, VERSION),
                    new online.yudream.base.plugin.mcpanel.application.service.AuditQueryService(context.documents(),
                            id -> instanceRepository.findById(id).map(online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance::name).orElse(null),
                            id -> nodeRepository.findById(id).map(online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode::name).orElse(null)),
                    quickStartService,
                    proxyGroupService,
                    metricsHistory,
                    modpackService,
                    coreDownloadService,
                    syncLinkService,
                    authlibInjectionService,
                    playtimeInjectionService,
                    entryRouteService,
                    uploadTaskService,
                    modpackUploadService,
                    framework.security()));
            context.onDispose(scheduleService::stop);
            context.onDispose(uploadTaskService::close);
            context.onDispose(eventTaskService::close);

            // 先注册清理，再启动资源：任何一步异常都会走同一释放路径。
            final NodeConnectionManager disposableManager = manager;
            final NodeEventBus disposableBus = bus;
            // 节点事件协调器：总线监听只快速入队（不阻塞 WebSocket 读线程），
            // state 落库走有界保序快速通道；计划任务命令下发与上线回读（同步 RPC）
            // 走独立慢通道，且上线回读按节点去重（替代原内联监听 + 无界 virtual 线程）。
            // 计划任务触发以实例服务的来源校验结果为门（onNodeInstanceEvent 返回值），
            // 跨节点伪造 instanceId 的事件不会触发他人计划任务。
            final McpanelInstanceAppService listenerService = instanceService;
            final McpanelEventCoordinator coordinator = new McpanelEventCoordinator(
                    listenerService::onNodeInstanceEvent,
                    listenerService::onNodeOffline,
                    // 上线回读完成后链式触发事件任务的自动启动（回读先行：拉起前状态已与节点对齐）。
                    nodeId -> {
                        int updated = listenerService.syncStatesFromNode(nodeId);
                        eventTaskService.onNodeOnline(nodeId);
                        return updated;
                    },
                    // stats 快照：状态纠偏 + 性能历史采样（实例维度与节点维度同一通道，先纠偏再采样）。
                    (nodeId, stats) -> {
                        int updated = listenerService.onNodeStatsSnapshot(nodeId, stats);
                        metricsHistory.onStats(nodeId, stats);
                        metricsHistory.onNodeStats(nodeId, stats);
                        return updated;
                    },
                    scheduleService::onInstanceEvent);
            disposableBus.addListener(coordinator);
            // 节点回报的 P2P 会话状态（connecting/direct/relayed/failed + 字节数）→ 会话状态机
            disposableBus.addListener((type, nodeId, payload) -> {
                if (online.yudream.base.plugin.mcpanel.infrastructure.node.NodeEventBus.TYPE_P2P_SESSION_STATE
                        .equals(type)) {
                    p2pSessionService.onNodeState(nodeId, payload);
                }
            });
            Thread restorer = new Thread(
                    () -> restoreEnabledNodes(nodeRepository, disposableManager), "mcpanel-node-restore");
            restorer.setDaemon(true);
            this.restorerThread = restorer;
            context.onDispose(() -> {
                disposableBus.removeListener(coordinator);
                coordinator.close();
                instanceFacade.closeOutputPool();
                restorer.interrupt();
                disposableBus.completeAll();
                disposableManager.close();
            });
            manager.start();
            bus.startHeartbeat();

            // 已存在且启用的节点异步逐页恢复（批次 100 = NodeQuery 派生上限；onEnable 保持轻装配）。
            restorer.start();
        } catch (RuntimeException failure) {
            if (bus != null) {
                bus.completeAll();
            }
            if (manager != null) {
                manager.close();
            }
            throw failure;
        }
    }

    private static boolean scopeIsPlatformAdmin(String scopeKey) {
        // M7 租户实体未上线：所有 manage 持有者视作平台范围（数据范围开关上线后收紧）。
        return true;
    }

    /** 网关随设置启停：关=停；开=端口未变不重启（保住在途传输），变了即重启换端口。 */
    private static void applySftpGateway(SftpGatewayServer server,
                                         online.yudream.base.plugin.mcpanel.application.dto.PanelSettings.SftpGateway config)
            throws IOException {
        if (config == null || !config.enabled() || config.port() < 1) {
            server.stop();
            return;
        }
        if (server.isRunning() && server.boundPort() == config.port()) {
            return;
        }
        server.start(config.port());
    }


    /**
     * 逐页恢复启用节点。批次必须取 100（repo 侧 NodeQuery.sizeOrDefault 上限 100，
     * 传 200 会被钳到 100 导致 "< 批次" 终止条件在第 1 页即退出、>100 节点截断）。
     * 封顶 500 页防异常数据死循环；onDispose/onDisable 通过中断本线程提前结束。
     *
     * @return 恢复（触发 syncNode）的启用节点数；包可见以便验收回归。
     */
    static int restoreEnabledNodes(McpanelNodeRepository nodeRepository, NodeControlPlane manager) {
        int restored = 0;
        try {
            for (int page = 1; page <= 500 && !Thread.currentThread().isInterrupted(); page++) {
                var result = nodeRepository.page(
                        new online.yudream.base.plugin.mcpanel.domain.valobj.NodeQuery(page, 100, null, null));
                for (var node : result.records()) {
                    if (node.enabled()) {
                        manager.syncNode(node);
                        restored++;
                    }
                }
                if (result.records().size() < 100) {
                    return restored;
                }
            }
        } catch (RuntimeException ignored) {
            // 恢复失败不阻断插件运行；管理端操作仍可触发 syncNode。
        }
        return restored;
    }

    @Override
    public void onDisable(PluginContext context) {
        NodeEventBus bus = eventBus;
        NodeConnectionManager manager = connectionManager;
        Thread restorer = restorerThread;
        if (restorer != null) {
            restorer.interrupt();
        }
        if (bus != null) {
            bus.completeAll();
        }
        if (manager != null) {
            manager.close();
        }
    }

    /** 供测试/运维诊断读取当前连接运行时（不暴露凭据）。 */
    public NodeControlPlane controlPlane() {
        return connectionManager;
    }

    /** 审计/用量计算的实例聚合读取代理（避免 lambda 直接引用聚合类型）。 */
    private static final class McpanelAggregateProxy {
        static long cpuOf(online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance instance) {
            return instance.cpuMillis();
        }

        static long memOf(online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance instance) {
            return instance.memoryMb();
        }
    }
}
