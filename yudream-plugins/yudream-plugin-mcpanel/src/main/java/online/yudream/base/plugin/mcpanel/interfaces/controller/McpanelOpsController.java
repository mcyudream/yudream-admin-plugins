package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.application.service.OverviewService;
import online.yudream.base.plugin.mcpanel.application.service.ScheduleService;
import online.yudream.base.plugin.mcpanel.application.service.ServerConfigService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 监控总览 / 计划任务 / server.properties / 代理纳管 / 批量实例操作。 */
public class McpanelOpsController {

    private final OverviewService overview;
    private final ScheduleService schedules;
    private final ServerConfigService serverConfig;
    private final McpanelInstanceAppService instances;
    private final online.yudream.base.plugin.mcpanel.application.service.ModrinthService modrinth;
    private final online.yudream.base.plugin.mcpanel.application.service.AuditQueryService auditQuery;
    private final online.yudream.base.plugin.mcpanel.application.service.QuickStartPackageService quickStart;
    private final online.yudream.base.plugin.mcpanel.application.service.ProxyGroupService proxyGroups;
    private final online.yudream.base.plugin.mcpanel.application.service.MetricsHistoryService metricsHistory;
    private final online.yudream.base.plugin.mcpanel.application.service.ModpackService modpacks;
    private final online.yudream.base.plugin.mcpanel.application.service.CoreDownloadService coreDownload;
    private final online.yudream.base.plugin.mcpanel.application.service.SyncLinkService syncLinks;
    private final online.yudream.base.plugin.mcpanel.application.service.AuthlibInjectionService authlibInjection;
    private final online.yudream.base.plugin.mcpanel.application.service.PlaytimeInjectionService playtimeInjection;
    private final online.yudream.base.plugin.mcpanel.application.service.EntryRouteService entryRoutes;
    private final online.yudream.base.plugin.mcpanel.application.service.UploadTaskService uploadTasks;
    private final online.yudream.base.plugin.mcpanel.application.service.ModpackUploadService modpackUploads;
    private final PluginSecurityService security;

    public McpanelOpsController(OverviewService overview, ScheduleService schedules,
                                ServerConfigService serverConfig, McpanelInstanceAppService instances,
                                online.yudream.base.plugin.mcpanel.application.service.ModrinthService modrinth,
                                online.yudream.base.plugin.mcpanel.application.service.AuditQueryService auditQuery,
                                online.yudream.base.plugin.mcpanel.application.service.QuickStartPackageService quickStart,
                                online.yudream.base.plugin.mcpanel.application.service.ProxyGroupService proxyGroups,
                                online.yudream.base.plugin.mcpanel.application.service.MetricsHistoryService metricsHistory,
                                online.yudream.base.plugin.mcpanel.application.service.ModpackService modpacks,
                                online.yudream.base.plugin.mcpanel.application.service.CoreDownloadService coreDownload,
                                online.yudream.base.plugin.mcpanel.application.service.SyncLinkService syncLinks,
                                online.yudream.base.plugin.mcpanel.application.service.AuthlibInjectionService authlibInjection,
                                online.yudream.base.plugin.mcpanel.application.service.PlaytimeInjectionService playtimeInjection,
                                online.yudream.base.plugin.mcpanel.application.service.EntryRouteService entryRoutes,
                                online.yudream.base.plugin.mcpanel.application.service.UploadTaskService uploadTasks,
                                online.yudream.base.plugin.mcpanel.application.service.ModpackUploadService modpackUploads,
                                PluginSecurityService security) {
        this.overview = overview;
        this.schedules = schedules;
        this.serverConfig = serverConfig;
        this.instances = instances;
        this.modrinth = modrinth;
        this.auditQuery = auditQuery;
        this.quickStart = quickStart;
        this.proxyGroups = proxyGroups;
        this.metricsHistory = metricsHistory;
        this.modpacks = modpacks;
        this.coreDownload = coreDownload;
        this.syncLinks = syncLinks;
        this.authlibInjection = authlibInjection;
        this.playtimeInjection = playtimeInjection;
        this.entryRoutes = entryRoutes;
        this.uploadTasks = uploadTasks;
        this.modpackUploads = modpackUploads;
        this.security = security;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/quickstart/packages", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse quickPackages(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(quickStart.page(intQ(request, "page", 1),
                        intQ(request, "size", 20), q(request, "keyword"))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/quickstart/packages/options", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse quickPackageOptions(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(Map.of("records", quickStart.options())));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/quickstart/packages", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse quickPackageSave(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            Map<String, Object> body = McpanelJson.mapper().convertValue(
                    McpanelJson.readMap(request.body()).node(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return PluginHttpResponse.ok(quickStart.save(body));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/quickstart/packages/{id}", permission = McpanelPlugin.DELETE_PERMISSION)
    public PluginHttpResponse quickPackageDelete(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.DELETE_PERMISSION, () -> {
            quickStart.delete(lastId(request.path()));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/quickstart/packages/{id}", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse quickPackageDetail(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(quickStart.get(lastId(request.path()))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/audit", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse auditPage(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(auditQuery.page(intQ(request, "page", 1),
                        intQ(request, "size", 20), q(request, "action"), q(request, "actor"),
                        q(request, "targetType"), q(request, "targetId"))));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/audit", permission = McpanelPlugin.DELETE_PERMISSION)
    public PluginHttpResponse auditClear(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.DELETE_PERMISSION,
                () -> PluginHttpResponse.ok(auditQuery.deleteFiltered(q(request, "action"), q(request, "actor"),
                        q(request, "targetType"), q(request, "targetId"))));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/audit/{logId}", permission = McpanelPlugin.DELETE_PERMISSION)
    public PluginHttpResponse auditDelete(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.DELETE_PERMISSION,
                () -> PluginHttpResponse.ok(auditQuery.delete(lastId(request.path()))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/audit/export", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse auditExport(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(auditQuery.exportCsv(q(request, "action"), q(request, "actor"),
                        q(request, "targetType"), q(request, "targetId"))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/modrinth/search", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse modrinthSearch(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(modrinth.search(q(request, "query"), q(request, "type"),
                        intQ(request, "page", 1), intQ(request, "size", 12))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/modrinth/resolve", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse modrinthResolve(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            return PluginHttpResponse.ok(modrinth.resolveInstall(body.string("projectId"),
                    body.string("gameVersion"), body.string("loader"), body.string("projectType"),
                    body.string("targetDir")));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/install-files", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse installFiles(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            List<Map<String, Object>> files = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode arr = body.node().get("files");
            if (arr != null && arr.isArray()) {
                arr.forEach(item -> files.add(Map.of(
                        "url", item.path("url").asText(""),
                        "path", item.path("path").asText(""))));
            }
            return PluginHttpResponse.ok(instances.install(scope(request), pathId(request), files));
        });
    }

    /** 重试安装：按最近一次落库的安装计划原样重建（核心/整合包安装失败或卡死后的恢复入口）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/install-retry", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse installRetry(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION,
                () -> PluginHttpResponse.ok(
                        instances.installRetry(HttpGuards.actorOf(request), scope(request), pathId(request))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/overview", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse overview(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(overview.overview()));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/schedules", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse schedulePage(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(schedules.page(q(request, "instanceId"),
                        intQ(request, "page", 1), intQ(request, "size", 20))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/schedules", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse scheduleSave(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            Map<String, Object> body = McpanelJson.mapper().convertValue(
                    McpanelJson.readMap(request.body()).node(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return PluginHttpResponse.ok(schedules.save(body));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/schedules/{id}", permission = McpanelPlugin.DELETE_PERMISSION)
    public PluginHttpResponse scheduleDelete(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.DELETE_PERMISSION, () -> {
            schedules.delete(lastId(request.path()));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/schedules/{id}/run", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse scheduleRun(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION,
                () -> PluginHttpResponse.ok(schedules.runNow(lastId(request.path()))));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{id}/server-config", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse serverConfigView(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(serverConfig.view(scope(request), pathId(request),
                        q(request, "path"))));
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/instances/{id}/server-config", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse serverConfigSave(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            Map<String, String> props = new LinkedHashMap<>();
            Map<String, Object> rawProps = McpanelJson.mapper().convertValue(body.node().get("properties"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            if (rawProps != null) {
                rawProps.forEach((key, value) -> props.put(key, value == null ? "" : String.valueOf(value)));
            }
            boolean preferRaw = Boolean.TRUE.equals(
                    body.node().hasNonNull("preferRaw") && body.node().get("preferRaw").asBoolean(false));
            return PluginHttpResponse.ok(serverConfig.save(scope(request), pathId(request),
                    body.string("path"), props, body.string("content"), preferRaw));
        });
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{id}/proxy-group", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse proxyGroupView(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> {
            Map<String, Object> group = proxyGroups.group(pathId(request));
            return PluginHttpResponse.ok(group == null ? Map.of("exists", false) : group);
        });
    }

    /** 识别（只读）：根目录特征判定代理类型，解析 servers 段并自动匹配面板实例。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/proxy-group/detect", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse proxyGroupDetect(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(proxyGroups.detect(scope(request), pathId(request))));
    }

    /** 纳管/更新绑定：servers[].boundInstanceId 为空 = 外部子服（仅记录）。 */
    @PluginHttpEndpoint(method = "PUT", path = "/admin/instances/{id}/proxy-group", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse proxyGroupSave(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            Map<String, Object> body = McpanelJson.mapper().convertValue(
                    McpanelJson.readMap(request.body()).node(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return PluginHttpResponse.ok(proxyGroups.save(HttpGuards.actorOf(request), pathId(request), body));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/instances/{id}/proxy-group", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse proxyGroupDelete(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            proxyGroups.delete(HttpGuards.actorOf(request), pathId(request));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    /** authlib 注入视图：面板注入源是否齐备、当前注入状态、生效 apiRoot。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{id}/authlib-injection", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse authlibInjectionView(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(authlibInjection.view(scope(request), pathId(request))));
    }

    /** 切换 authlib 注入（停机才可切换）：开启下发 jar + 启动命令追加 -javaagent，关闭移除。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/authlib-injection", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse authlibInjectionApply(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            if (!McpanelJson.readMap(request.body()).node().hasNonNull("enabled")) {
                throw new IllegalArgumentException("缺少 enabled 字段");
            }
            boolean enabled = McpanelJson.readMap(request.body()).node().get("enabled").asBoolean(false);
            return PluginHttpResponse.ok(authlibInjection.apply(HttpGuards.actorOf(request),
                    scope(request), pathId(request), enabled));
        });
    }

    /** 在线时长注入视图：制品矩阵可用性、匹配制品与当前注入状态（目录内存在固定名制品）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{id}/playtime-injection", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse playtimeInjectionView(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(playtimeInjection.view(scope(request), pathId(request))));
    }

    /** 切换在线时长注入（停机才可切换）：开启按制品矩阵下载放入插件/模组目录，关闭删除。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/playtime-injection", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse playtimeInjectionApply(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            if (!McpanelJson.readMap(request.body()).node().hasNonNull("enabled")) {
                throw new IllegalArgumentException("缺少 enabled 字段");
            }
            boolean enabled = McpanelJson.readMap(request.body()).node().get("enabled").asBoolean(false);
            return PluginHttpResponse.ok(playtimeInjection.apply(HttpGuards.actorOf(request),
                    scope(request), pathId(request), enabled));
        });
    }

    // ---------- 大文件分片上传（异步任务：浏览器分片直传面板，面板流式中转节点） ----------

    /** 上传任务列表（running + 5 分钟内终态）：重进文件页/详情页恢复进度显示。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{id}/upload-tasks", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse uploadTaskList(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(Map.of("tasks", uploadTasks.viewOf(pathId(request)))));
    }

    /** 建任务并申请节点上传会话（body: path/size/sha256/name，sha256 由浏览器整文件预计算）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/upload-tasks", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse uploadTaskBegin(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            com.fasterxml.jackson.databind.JsonNode sizeNode = body.node().get("size");
            long size = sizeNode != null && sizeNode.canConvertToLong() ? sizeNode.asLong() : -1L;
            return PluginHttpResponse.ok(uploadTasks.begin(scope(request), pathId(request),
                    body.string("path"), size, body.string("sha256"), body.string("name")));
        });
    }

    /** 接收一个分片（multipart "data"，query: taskId/offset），面板拆 96KiB 帧转发节点。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/upload-tasks/chunk", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse uploadTaskChunk(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            String taskId = q(request, "taskId");
            long offset = longQ(request, "offset", -1L);
            for (online.yudream.base.plugin.spi.http.PluginHttpPart part : request.parts().values()) {
                if (part.isFile()) {
                    return PluginHttpResponse.ok(uploadTasks.chunk(scope(request), taskId, offset, part.data()));
                }
            }
            throw new IllegalArgumentException("缺少分片数据（multipart data）");
        });
    }

    /** 完成上传：节点校验 size+sha256 后落盘。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/upload-tasks/commit", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse uploadTaskCommit(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION,
                () -> PluginHttpResponse.ok(uploadTasks.commit(scope(request), q(request, "taskId"))));
    }

    /** 取消上传：节点 abort 回收临时分片。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/upload-tasks/cancel", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse uploadTaskCancel(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION,
                () -> PluginHttpResponse.ok(uploadTasks.cancel(scope(request), q(request, "taskId"))));
    }

    /** 实例域名视图（本地状态，不发外部请求）：分配状态、连接地址与不可用原因。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{id}/domain", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse domainView(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(instances.domainView(scope(request), pathId(request))));
    }

    /** 分配/重新同步域名：写 A 记录（Java 版再写 SRV），幂等，换节点/换端口后可重复调用。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/domain", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse domainAssign(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            boolean srv = !Boolean.FALSE.equals(McpanelJson.readMap(request.body()).bool("srv"));
            return PluginHttpResponse.ok(instances.domainAssign(HttpGuards.actorOf(request),
                    scope(request), pathId(request), srv));
        });
    }

    /** 释放域名：删除 A/SRV 记录并关闭该实例的自动分配开关。 */
    @PluginHttpEndpoint(method = "DELETE", path = "/admin/instances/{id}/domain", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse domainRelease(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION,
                () -> PluginHttpResponse.ok(instances.domainRelease(HttpGuards.actorOf(request),
                        scope(request), pathId(request))));
    }

    /** 实时校验解析：回读云解析记录并与预期值比对（排障用，会外呼云商）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/domain/verify", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse domainVerify(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> {
            boolean srv = !Boolean.FALSE.equals(McpanelJson.readMap(request.body()).bool("srv"));
            return PluginHttpResponse.ok(instances.domainVerify(scope(request), pathId(request), srv));
        });
    }

    /** PROXY protocol 开关状态（单端口入口配套：实例侧需允许接收入口转发的真实玩家 IP）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{id}/proxy-protocol", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse proxyProtocolView(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> PluginHttpResponse.ok(
                serverConfig.proxyProtocolStatus(scope(request), pathId(request),
                        instances.instanceKindOf(scope(request), pathId(request)))));
    }

    /** 一键开启/关闭实例的 PROXY protocol（只改配置文件里已存在的键，重启后生效）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/proxy-protocol", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse proxyProtocolToggle(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            if (!McpanelJson.readMap(request.body()).node().hasNonNull("enabled")) {
                throw new IllegalArgumentException("缺少 enabled 字段");
            }
            boolean enabled = McpanelJson.readMap(request.body()).node().get("enabled").asBoolean(false);
            String kind = instances.instanceKindOf(scope(request), pathId(request));
            return PluginHttpResponse.ok(serverConfig.setProxyProtocol(scope(request), pathId(request),
                    kind, enabled));
        });
    }

    /** 单端口入口（mc-router）状态：配置齐备性、连通性、期望路由与差异（设置页用）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/entry/status", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse entryStatus(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION,
                () -> PluginHttpResponse.ok(entryRoutes.status()));
    }

    /** 手动触发入口路由对账：补推缺失/不一致、删除本面板域后缀下的多余路由。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/entry/reconcile", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse entryReconcile(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION,
                () -> PluginHttpResponse.ok(entryRoutes.reconcile()));
    }

    /** 实例性能历史（环形窗口，默认 1h；windowMs 上限 24h，服务端裁剪）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{id}/metrics", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse instanceMetrics(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(metricsHistory.view(pathId(request), longQ(request, "windowMs", 3_600_000L))));
    }

    /** 整合包上传解析（mrpack / CurseForge zip）：CF manifest 会外呼 cfwidget 解析文件名，可能耗时数十秒。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/modpacks/inspect", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse modpackInspect(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            online.yudream.base.plugin.spi.http.PluginHttpPart file = null;
            for (online.yudream.base.plugin.spi.http.PluginHttpPart part : request.parts().values()) {
                if (part.isFile()) {
                    file = part;
                    break;
                }
            }
            if (file == null) {
                throw new IllegalArgumentException("缺少整合包文件");
            }
            online.yudream.base.plugin.mcpanel.application.service.ModpackService.ImportResult result =
                    modpacks.inspect(file.data());
            String token = online.yudream.base.plugin.mcpanel.application.service.ModpackInspectStore.newToken();
            online.yudream.base.plugin.mcpanel.application.service.ModpackInspectStore.put(token, file.filename(), result);
            return PluginHttpResponse.ok(
                    online.yudream.base.plugin.mcpanel.application.service.ModpackService.summarize(token, file.filename(), result));
        });
    }

    // ---------- 整合包分片上传（实例创建场景：实例尚不存在，走面板侧暂存缓冲） ----------

    /** 建立分片上传任务：浏览器先流式算整文件 sha256，再按 ~4MiB 分片直传。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/modpacks/upload-tasks", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse modpackUploadBegin(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            String size = body.string("size");
            return PluginHttpResponse.ok(modpackUploads.begin(
                    body.string("name"),
                    size == null || size.isBlank() ? 0L : Long.parseLong(size.trim()),
                    body.string("sha256")));
        });
    }

    /** 接收一个分片（顺序到达，offset=已收字节）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/modpacks/upload-tasks/chunk", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse modpackUploadChunk(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            online.yudream.base.plugin.spi.http.PluginHttpPart data = request.parts().get("data");
            if (data == null) {
                for (online.yudream.base.plugin.spi.http.PluginHttpPart part : request.parts().values()) {
                    if (part.isFile()) {
                        data = part;
                        break;
                    }
                }
            }
            if (data == null) {
                throw new IllegalArgumentException("缺少分片内容（data）");
            }
            return PluginHttpResponse.ok(modpackUploads.chunk(
                    q(request, "taskId"),
                    longQ(request, "offset", -1L),
                    data.data()));
        });
    }

    /** 完成上传：校验 size+sha256 后同步解析，返回与旧 inspect 相同的摘要（含 token）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/modpacks/upload-tasks/commit", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse modpackUploadCommit(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            return PluginHttpResponse.ok(modpackUploads.commit(body.string("taskId")));
        });
    }

    /** 取消/放弃上传：释放面板侧缓冲。 */
    @PluginHttpEndpoint(method = "DELETE", path = "/admin/modpacks/upload-tasks", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse modpackUploadCancel(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION,
                () -> PluginHttpResponse.ok(modpackUploads.cancel(q(request, "taskId"))));
    }

    /** 创建实例后应用整合包：核心（统一 server.jar）+ 全部文件计划一次 install.run + overrides 逐个写入。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/modpack-apply", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse modpackApply(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            String token = body.string("token");
            online.yudream.base.plugin.mcpanel.application.service.ModpackService.ImportResult pack =
                    online.yudream.base.plugin.mcpanel.application.service.ModpackInspectStore.take(token);
            if (pack == null) {
                throw new IllegalArgumentException("解析结果已过期或已使用，请重新上传整合包");
            }
            if (pack.mcVersion() == null || pack.mcVersion().isBlank()) {
                throw new IllegalArgumentException("整合包缺少 MC 版本，无法自动选择服务端核心");
            }
            String coreKind = pack.coreChain().isEmpty() ? "paper" : pack.coreChain().get(0);
            online.yudream.base.plugin.mcpanel.application.service.CoreDownloadService.CoreDownloadPlan core =
                    coreDownload.resolve(coreKind, pack.mcVersion(), pack.loaderVersion());
            List<Map<String, Object>> files = new ArrayList<>();
            Map<String, Object> coreFile = new LinkedHashMap<>();
            coreFile.put("url", core.url());
            coreFile.put("path", "server.jar");
            files.add(coreFile);
            files.addAll(pack.plan());
            instances.install(scope(request), pathId(request), files);
            int overrides = 0;
            for (Map<String, Object> item : pack.overrides()) {
                instances.files(scope(request), pathId(request), "write", Map.of(
                        "path", String.valueOf(item.get("path")),
                        "content", String.valueOf(item.get("contentB64")),
                        "encoding", "base64"));
                overrides++;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("applied", files.size());
            result.put("overrides", overrides);
            result.put("coreKind", coreKind);
            result.put("coreSource", core.source());
            return PluginHttpResponse.ok(result);
        });
    }

    /** 已纳管代理列表（创建向导「挂到代理」选择器数据源）。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/proxy-groups", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse proxyGroupsList(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION,
                () -> PluginHttpResponse.ok(Map.of("records", proxyGroups.listGroups())));
    }

    /** 创建子服后自动挂到代理：组绑定 + 自动写代理配置（需代理已纳管）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/proxy-group/attach", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse proxyAttach(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            return PluginHttpResponse.ok(proxyGroups.attachChild(HttpGuards.actorOf(request),
                    scope(request), pathId(request), body.string("instanceId")));
        });
    }

    /** 子服软链接（独立/同步模式）：查询/建立/解除/立即同步。 */
    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{id}/sync-link", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse syncLinkView(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> {
            Map<String, Object> link = syncLinks.linkOf(pathId(request));
            return PluginHttpResponse.ok(link == null ? Map.of("exists", false) : link);
        });
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/sync-link", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse syncLinkSave(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            List<String> paths = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode rawPaths = body.node().get("paths");
            if (rawPaths != null && rawPaths.isArray()) {
                rawPaths.forEach(item -> paths.add(item.asText("")));
            }
            return PluginHttpResponse.ok(syncLinks.link(HttpGuards.actorOf(request), pathId(request),
                    body.string("sourceInstanceId"), paths));
        });
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/instances/{id}/sync-link", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse syncLinkDelete(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            syncLinks.unlink(HttpGuards.actorOf(request), pathId(request));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    /** 立即同步（源打 zip → 面板中转 → 目标覆盖；大包有 512MB 上限）。 */
    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{id}/sync-link/sync", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse syncLinkRun(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION,
                () -> PluginHttpResponse.ok(syncLinks.syncNow(HttpGuards.actorOf(request),
                        scope(request), pathId(request))));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/batch", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse batchInstances(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            String action = body.string("action");
            List<String> ids = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode rawIds = body.node().get("ids");
            if (rawIds != null && rawIds.isArray()) {
                rawIds.forEach(item -> {
                    String id = item.asText("");
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                });
            }
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("请选择实例");
            }
            if (ids.size() > 50) {
                throw new IllegalArgumentException("单次最多操作 50 个实例");
            }
            List<Map<String, Object>> results = new ArrayList<>();
            String actor = HttpGuards.actorOf(request);
            String scope = scope(request);
            for (String id : ids) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                try {
                    Map<String, Object> updated = instances.action(actor, scope, id,
                            action == null ? "" : action, null);
                    row.put("ok", true);
                    row.put("state", updated.get("state"));
                } catch (RuntimeException error) {
                    row.put("ok", false);
                    row.put("error", error.getMessage());
                }
                results.add(row);
            }
            return PluginHttpResponse.ok(Map.of("results", results));
        });
    }

    private static String scope(PluginHttpRequest request) {
        Long userId = HttpGuards.principalUserId(request);
        return userId == null ? "anonymous" : "user:" + userId;
    }

    private static String pathId(PluginHttpRequest request) {
        return lastId(request.path());
    }

    private static String lastId(String path) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // /admin/instances/{id}/server-config → 取 instances 后一段
        String[] parts = trimmed.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("instances".equals(parts[i]) || "schedules".equals(parts[i])) {
                if (i + 1 < parts.length && !parts[i + 1].isBlank()) {
                    return java.net.URLDecoder.decode(parts[i + 1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        int slash = trimmed.lastIndexOf('/');
        String last = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (last.isBlank()) {
            throw new IllegalArgumentException("路径资源 ID 不能为空");
        }
        return java.net.URLDecoder.decode(last, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String q(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0).trim();
    }

    private static int intQ(PluginHttpRequest request, String key, int defaultValue) {
        return (int) longQ(request, key, defaultValue);
    }

    private static long longQ(PluginHttpRequest request, String key, long defaultValue) {
        String value = q(request, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return defaultValue;
        }
    }
}
