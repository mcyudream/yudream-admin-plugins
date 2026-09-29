package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.spi.http.PluginHttpPart;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例 HTTP 边界（M2/M3）：CRUD、生命周期、控制台、文件、备份、任务。
 * scopeKey = "user:{userId}"（租户范围在应用层解析）。
 */
public class McpanelInstanceFacade {

    private final McpanelInstanceAppService instances;
    private final PluginSecurityService security;
    private final online.yudream.base.plugin.mcpanel.application.service.TemplateService templates;
    private final online.yudream.base.plugin.mcpanel.application.service.DockerImageService dockerImages;
    private final online.yudream.base.plugin.mcpanel.application.service.InstancePlayersService playersService;
    private final online.yudream.base.plugin.mcpanel.application.service.ProxyGroupService proxyGroups;
    private final online.yudream.base.plugin.mcpanel.application.service.InstallTaskTracker installTracker;
    private final online.yudream.base.plugin.mcpanel.application.service.InstanceStateResolver stateResolver;

    /** 实例输出 attach 引用计数池：最后一个 SSE 订阅离开才异步 detach（防多浏览器互踢）。 */
    private final OutputAttachPool outputPool;
    /** 事件总线泛化视图开启器（instance.state / node.stats 等统一事件流使用）。 */
    private OutputEventsOpener topicOpener;

    /** 实例输出 SSE 流开启器（bootstrap 注入节点事件总线的过滤视图）。 */
    public interface OutputEventsOpener {
        PluginSseStream open(String nodeId, String instanceId);

        /** 泛化主题视图：instance.state / node.stats 等按节点或实例过滤。 */
        PluginSseStream openTopic(String nodeId, String eventType, String matchKey, String matchValue);
    }

    /**
     * 实例统一事件流：控制台输出（attach 泵）+ 实例状态 + 节点统计合并为一条 SSE。
     *
     * <p>作用域经 {@code scopes} 查询参数声明（逗号分隔的 {@code topic:instanceId}，
     * topic ∈ instance.output / instance.state / node.stats，node.stats 取该实例所在
     * 节点）；每个作用域都过数据边界校验，帧事件名与旧单流完全一致，前端按事件名
     * 分发。连接关闭即整体拆除（输出源经 attach 池引用计数递减）。
     */
    public PluginHttpResponse instanceEvents(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, () -> {
            String scopesParam = query(request, "scopes");
            if (scopesParam == null || scopesParam.isBlank()) {
                throw new McpanelBusinessException("events.scopes-required", 400,
                        "缺少 scopes 参数（topic:instanceId 逗号分隔）");
            }
            record Scope(String topic, String instanceId) {
            }
            java.util.LinkedHashMap<String, Scope> scopes = new java.util.LinkedHashMap<>();
            for (String raw : scopesParam.split(",")) {
                String scope = raw.trim();
                int sep = scope.indexOf(':');
                String topic = sep <= 0 ? "" : scope.substring(0, sep);
                String instanceId = sep <= 0 ? "" : scope.substring(sep + 1);
                if (!List.of("instance.output", "instance.state", "node.stats").contains(topic)
                        || instanceId.isBlank()) {
                    throw new McpanelBusinessException("events.bad-scope", 400, "非法事件作用域：" + scope);
                }
                scopes.putIfAbsent(topic + ":" + instanceId, new Scope(topic, instanceId));
            }
            List<PluginSseStream> sources = new java.util.ArrayList<>();
            for (Scope scope : scopes.values()) {
                // 数据边界：每个作用域的实例都必须在调用方可见范围内。
                McpanelInstance instance = instances.accessibleInstance(scope(request), scope.instanceId());
                String nodeId = instances.nodeInstanceOf(scope(request), scope.instanceId());
                switch (scope.topic()) {
                    case "instance.output" -> sources.add(outputPool.open(nodeId, instance.id()));
                    case "instance.state" ->
                            sources.add(topicOpener.openTopic(nodeId, "instance.state", "instanceId", instance.id()));
                    case "node.stats" ->
                            sources.add(topicOpener.openTopic(nodeId, "node.stats", "nodeId", nodeId));
                    default -> throw new McpanelBusinessException("events.bad-topic", 400, "不支持的事件类型");
                }
            }
            PluginSseStream merged = InstanceEventStreamMux.merge(sources.toArray(new PluginSseStream[0]));
            return new PluginHttpResponse(200,
                    Map.of("Cache-Control", "no-cache", "Connection", "keep-alive", "X-Accel-Buffering", "no"),
                    "text/event-stream", merged, false);
        });
    }

    public McpanelInstanceFacade(McpanelInstanceAppService instances, PluginSecurityService security,
                                 online.yudream.base.plugin.mcpanel.application.service.TemplateService templates,
                                 online.yudream.base.plugin.mcpanel.application.service.DockerImageService dockerImages,
                                 online.yudream.base.plugin.mcpanel.application.service.InstancePlayersService playersService,
                                 OutputEventsOpener outputEvents,
                                 online.yudream.base.plugin.mcpanel.application.service.ProxyGroupService proxyGroups,
                                 online.yudream.base.plugin.mcpanel.application.service.InstallTaskTracker installTracker,
                                 online.yudream.base.plugin.mcpanel.application.service.InstanceStateResolver stateResolver) {
        this.instances = instances;
        this.security = security;
        this.templates = templates;
        this.dockerImages = dockerImages;
        this.playersService = playersService;
        this.proxyGroups = proxyGroups;
        this.installTracker = installTracker;
        this.stateResolver = stateResolver;
        this.outputPool = new OutputAttachPool(instances, outputEvents::open);
        this.topicOpener = outputEvents;
        // 实例（重）启动后为存活订阅重挂节点输出泵（实例停止时节点回收泵，浏览器不刷新就没有实时输出）。
        instances.setOutputReattachListener(outputPool::reattachIfSubscribed);
    }

    /** 插件卸载时关停 attach 池的异步线程（bootstrap onDispose 注册）。幂等。 */
    public void closeOutputPool() {
        outputPool.close();
    }

    /** 供事件任务服务在自动启动成功后重挂输出泵（bootstrap 接线）。 */
    public void reattachOutput(String instanceId) {
        outputPool.reattachIfSubscribed(instanceId);
    }

    public PluginHttpResponse page(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> {
            Map<String, Object> result = instances.page(scope(request),
                    intQuery(request, "page", 1), intQuery(request, "size", 10),
                    query(request, "keyword"), query(request, "status"), query(request, "nodeId"));
            // 响应组装层附加实时状态（liveState）、代理父子摘要（proxy / proxyChildren）与安装任务进度。
            stateResolver.decoratePage(result);
            proxyGroups.decoratePage(result);
            decorateInstall(result);
            return PluginHttpResponse.ok(result);
        });
    }

    public PluginHttpResponse detail(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> {
            Map<String, Object> dto = instances.detail(scope(request), segment(request.path(), 3));
            stateResolver.decorateDetail(dto);
            proxyGroups.decorateDetail(dto);
            decorateInstall(dto);
            return PluginHttpResponse.ok(dto);
        });
    }

    /** 列表/详情 DTO 附加 installTask（有活动/近期安装任务时）。 */
    @SuppressWarnings("unchecked")
    private void decorateInstall(Object target) {
        if (installTracker == null || !(target instanceof Map)) {
            return;
        }
        Map<String, Object> container = (Map<String, Object>) target;
        Object records = container.get("records");
        if (records instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> record) {
                    attachInstallTask((Map<String, Object>) record);
                }
            }
        }
        else {
            attachInstallTask(container);
        }
    }

    private void attachInstallTask(Map<String, Object> dto) {
        Object id = dto.get("id");
        if (id == null) {
            return;
        }
        Map<String, Object> task = installTracker.viewOf(String.valueOf(id));
        if (task != null) {
            dto.put("installTask", task);
        }
    }

    public PluginHttpResponse create(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelInstance spec = expandCreateSpec(request.body());
            if (spec.id() == null || spec.id().isBlank()) {
                throw new IllegalArgumentException("instanceId 必填");
            }
            return PluginHttpResponse.ok(instances.create(HttpGuards.actorOf(request), scope(request), spec));
        });
    }

    /**
     * 引导式创建：可选 templateKey（服务端模板，含 installer 自动下载核心）、
     * dockerImageId（管理员镜像映射）、autoDownloadCore。缺省字段由模板/映射展开，
     * 手动填写优先级更高。
     */
    @SuppressWarnings("unchecked")
    private McpanelInstance expandCreateSpec(String body) {
        McpanelJson.MapReader reader = McpanelJson.readMap(body);
        McpanelInstance base = specOf(body);
        String templateKey = reader.string("templateKey");
        String dockerImageId = reader.string("dockerImageId");
        boolean autoDownloadCore = reader.node().hasNonNull("autoDownloadCore")
                && reader.node().get("autoDownloadCore").asBoolean(false);
        if ((templateKey == null || templateKey.isBlank())
                && (dockerImageId == null || dockerImageId.isBlank())) {
            return base;
        }

        String kind = base.kind();
        String mcVersion = base.mcVersion();
        String image = base.image();
        List<String> command = base.command();
        Map<String, String> env = new LinkedHashMap<>(base.env() == null ? Map.of() : base.env());
        Map<String, String> config = new LinkedHashMap<>(base.config() == null ? Map.of() : base.config());
        String resolvedTemplateKey = base.templateKey();

        if (templateKey != null && !templateKey.isBlank()) {
            Map<String, Object> expanded = templates.expand(templateKey, mcVersion.isBlank() ? null : mcVersion);
            resolvedTemplateKey = templateKey;
            Object expandedKind = expanded.get("kind");
            if (expandedKind != null && !"image".equals(String.valueOf(expandedKind))) {
                kind = String.valueOf(expandedKind);
            }
            Object expandedVersion = expanded.get("mcVersion");
            if (expandedVersion != null && !String.valueOf(expandedVersion).isBlank()) {
                mcVersion = String.valueOf(expandedVersion);
            }
            Object expandedImage = expanded.get("image");
            if ((image == null || image.isBlank()) && expandedImage != null) {
                image = String.valueOf(expandedImage);
            }
            Object expandedCommand = expanded.get("command");
            if (expandedCommand instanceof List<?> list && !list.isEmpty()) {
                // 模板创建：以模板 startup（含 jarGlob）为准，避免默认 server.jar 与真实核心名不一致
                command = list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
            }
            Object expandedEnv = expanded.get("env");
            if (expandedEnv instanceof Map<?, ?> map) {
                map.forEach((key, value) -> {
                    if (key != null && value != null) {
                        env.putIfAbsent(String.valueOf(key), String.valueOf(value));
                    }
                });
            }
            Object installer = expanded.get("installer");
            if (autoDownloadCore && installer instanceof Map<?, ?> installerMap
                    && installerMap.get("type") != null
                    && !"blank".equals(String.valueOf(installerMap.get("type")))) {
                config.put("installType", String.valueOf(installerMap.get("type")));
                if (installerMap.get("url") != null) {
                    config.put("installUrl", String.valueOf(installerMap.get("url")));
                }
                if (mcVersion != null && !mcVersion.isBlank()) {
                    config.put("installMcVersion", mcVersion);
                }
                config.put("installFileName", jarFromCommand(command));
                config.put("autoDownloadCore", "true");
            }
        }

        if (dockerImageId != null && !dockerImageId.isBlank()) {
            Map<String, Object> mapping = dockerImages.get(dockerImageId);
            if (mapping == null) {
                // 目录已被删除：不回填、不阻断创建（保留用户手选镜像），也不写残留引用。
                return base;
            }
            if (image == null || image.isBlank()) {
                image = String.valueOf(mapping.getOrDefault("primaryImage",
                        mapping.getOrDefault("image", "")));
            }
            if (mcVersion == null || mcVersion.isBlank()) {
                // 目录与核心解耦：不再从镜像映射回填 mcVersion
            }
            config.put("dockerImageId", dockerImageId);
            config.put("dockerImageName", String.valueOf(mapping.getOrDefault("name", "")));
        }

        return new McpanelInstance(
                base.id(), base.nodeId(), base.name(), kind, mcVersion, resolvedTemplateKey,
                image, command, env, base.memoryMb(), base.cpuMillis(), base.diskMb(),
                base.ports(), config, base.state(), base.lastExitCode(), base.mcServerId(),
                base.tenantId(), base.remark(), base.domainSlug(), base.domainEnabled(),
                base.p2pEnabled(), base.p2pWhitelist(), base.nodeTrust(), base.modpack(),
                base.coreFallbackHistory(), base.startDetect(),
                base.autoRestart(), base.autoStart(), base.createdAt(), base.updatedAt());
    }

    public PluginHttpResponse update(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () ->
                PluginHttpResponse.ok(instances.update(HttpGuards.actorOf(request), scope(request),
                        segment(request.path(), 3), specOf(request.body()))));
    }

    /** 端口管理视图：列表（主端口标记/访问地址）+ 池范围（前端提示）。 */
    public PluginHttpResponse portsView(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () ->
                PluginHttpResponse.ok(instances.portsView(scope(request), segment(request.path(), 3))));
    }

    /** 开放附加端口：proto 缺省 tcp，port 缺省 = 池内自动分配。 */
    public PluginHttpResponse portAdd(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader reader = McpanelJson.readMap(request.body());
            String proto = reader.string("proto");
            if (proto == null || proto.isBlank()) {
                proto = "tcp";
            }
            Integer port = reader.node().hasNonNull("port") && reader.node().get("port").canConvertToInt()
                    ? reader.node().get("port").asInt()
                    : null;
            return PluginHttpResponse.ok(instances.addPort(HttpGuards.actorOf(request), scope(request),
                    segment(request.path(), 3), proto, port));
        });
    }

    /** 回收附加端口：DELETE /ports/{port}?proto=tcp；主端口由用例层拒绝。 */
    public PluginHttpResponse portRemove(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            String portText = segment(request.path(), 5);
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("端口无效：" + portText);
            }
            String proto = query(request, "proto");
            if (proto == null || proto.isBlank()) {
                proto = "tcp";
            }
            return PluginHttpResponse.ok(instances.removePort(HttpGuards.actorOf(request), scope(request),
                    segment(request.path(), 3), port, proto));
        });
    }

    /** 事件触发型任务开关（对标 MCSM eventTask）：运行中可随时保存，不触达节点。 */
    public PluginHttpResponse eventTaskSave(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            return PluginHttpResponse.ok(instances.saveEventTask(HttpGuards.actorOf(request), scope(request),
                    segment(request.path(), 3),
                    Boolean.TRUE.equals(body.bool("autoRestart")),
                    Boolean.TRUE.equals(body.bool("autoStart"))));
        });
    }

    public PluginHttpResponse action(PluginHttpRequest request, String action) {
        String permission = List.of("start", "stop", "restart", "kill").contains(action)
                ? McpanelPlugin.USE_PERMISSION : McpanelPlugin.MANAGE_PERMISSION;
        return HttpGuards.guarded(request, security, permission, () -> PluginHttpResponse.ok(
                instances.action(HttpGuards.actorOf(request), scope(request), segment(request.path(), 3),
                        action, intQueryOrNull(request, "timeoutSec"))));
    }

    public PluginHttpResponse delete(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.DELETE_PERMISSION, () ->
                PluginHttpResponse.ok(instances.delete(HttpGuards.actorOf(request), scope(request),
                        segment(request.path(), 3), booleanQuery(request, "purge"))));
    }

    public PluginHttpResponse command(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            String command = body.string("command");
            if (command == null || command.isBlank() || command.length() > 4096
                    || command.contains("\n") || command.contains("\r")) {
                throw new IllegalArgumentException("命令必须为单行且不超过 4096 字符");
            }
            return PluginHttpResponse.ok(instances.command(HttpGuards.actorOf(request), scope(request),
                    segment(request.path(), 3), command));
        });
    }

    public PluginHttpResponse output(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () ->
                PluginHttpResponse.ok(instances.output(scope(request), segment(request.path(), 3),
                        query(request, "since"), intQueryOrNull(request, "tail"))));
    }

    /** TPS 探测：固定代发 tps 控制台命令（Paper 系），输出经控制台流返回由前端解析。 */
    public PluginHttpResponse tpsProbe(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, () ->
                PluginHttpResponse.ok(instances.tpsProbe(scope(request), segment(request.path(), 3))));
    }

    /** 在线玩家探测（server list ping，服务端带 TTL 缓存）。 */
    public PluginHttpResponse players(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () ->
                PluginHttpResponse.ok(playersService.players(scope(request), segment(request.path(), 3))));
    }

    public PluginHttpResponse files(PluginHttpRequest request, String operation) {
        String permission = switch (operation) {
            case "list", "read", "download" -> McpanelPlugin.VIEW_PERMISSION;
            default -> McpanelPlugin.MANAGE_PERMISSION;
        };
        return HttpGuards.guarded(request, security, permission, () ->
                PluginHttpResponse.ok(instances.files(scope(request), segment(request.path(), 3), operation,
                        argsOf(request))));
    }

    public PluginHttpResponse fileZip(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () ->
                PluginHttpResponse.ok(instances.files(scope(request), segment(request.path(), 3), "zip",
                        argsOf(request))));
    }

    public PluginHttpResponse fileUnzip(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () ->
                PluginHttpResponse.ok(instances.files(scope(request), segment(request.path(), 3), "unzip",
                        argsOf(request))));
    }

    public PluginHttpResponse upload(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            Map<String, online.yudream.base.plugin.spi.http.PluginHttpPart> parts = request.parts();
            if (parts == null || parts.isEmpty()) {
                throw new IllegalArgumentException("缺少上传文件");
            }
            PluginHttpPart file = parts.values().iterator().next();
            if (!file.isFile()) {
                throw new IllegalArgumentException("上传部分必须是文件");
            }
            String path = query(request, "path");
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("缺少目标路径");
            }
            // 协议 §4：sha256 对原始文件字节求摘要（不得对 base64 字符串求值，否则 commit 校验必失败）。
            String sha256 = online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets
                    .sha256Hex(file.data());
            return PluginHttpResponse.ok(instances.upload(scope(request), segment(request.path(), 3), path,
                    file.data(), sha256));
        });
    }

    public PluginHttpResponse backup(PluginHttpRequest request, String operation) {
        // 列表是页面查看的纯读：VIEW 即可；其余（创建/恢复/删除/触发）要求 MANAGE。
        String permission = "list".equals(operation)
                ? McpanelPlugin.VIEW_PERMISSION : McpanelPlugin.MANAGE_PERMISSION;
        return HttpGuards.guarded(request, security, permission, () ->
                PluginHttpResponse.ok(instances.backup(HttpGuards.actorOf(request), scope(request),
                        segment(request.path(), 3), operation, argsOf(request.body()))));
    }

    /** 手动异地备份：body 携带目标编码，经宿主备份中心异步执行。 */
    public PluginHttpResponse backupTrigger(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () ->
                PluginHttpResponse.ok(instances.backup(HttpGuards.actorOf(request), scope(request),
                        segment(request.path(), 3), "trigger", argsOf(request.body()))));
    }

    /** 备份保留策略视图。 */
    public PluginHttpResponse backupPolicy(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () ->
                PluginHttpResponse.ok(instances.backupPolicy(scope(request), segment(request.path(), 3))));
    }

    /** 保存备份保留策略（keepCount/keepDays，0=不限），节点可用时立即清理一次。 */
    public PluginHttpResponse backupPolicySave(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            return PluginHttpResponse.ok(instances.saveBackupPolicy(HttpGuards.actorOf(request), scope(request),
                    segment(request.path(), 3), body.node().get("keepCount"), body.node().get("keepDays")));
        });
    }

    public PluginHttpResponse imagePull(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            String image = body.string("image");
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("缺少镜像名");
            }
            return PluginHttpResponse.ok(instances.imagePull(scope(request), segment(request.path(), 3), image));
        });
    }

    public PluginHttpResponse imageList(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () ->
                PluginHttpResponse.ok(instances.imageList(scope(request), segment(request.path(), 3))));
    }

    public PluginHttpResponse nodeContainers(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () ->
                PluginHttpResponse.ok(instances.nodeContainers(scope(request), resourceAfter(request.path(), "nodes"))));
    }

    public PluginHttpResponse imageRemove(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, () -> {
            String image = query(request, "image");
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("缺少镜像名");
            }
            boolean force = booleanQuery(request, "force");
            return PluginHttpResponse.ok(instances.imageRemove(scope(request), segment(request.path(), 3), image, force));
        });
    }

    public PluginHttpResponse task(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, () -> {
            String taskId = query(request, "taskId");
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException("缺少 taskId");
            }
            return PluginHttpResponse.ok(instances.task(scope(request), segment(request.path(), 3), taskId));
        });
    }

    // ---------- 内部 ----------

    private String scope(PluginHttpRequest request) {
        Long userId = HttpGuards.principalUserId(request);
        return userId == null ? "anonymous" : "user:" + userId;
    }

    private McpanelInstance specOf(String body) {
        return McpanelJson.read(body, McpanelInstance.class);
    }

    private Map<String, Object> argsOf(String body) {
        McpanelJson.MapReader reader = McpanelJson.readMap(body);
        Map<String, Object> args = new LinkedHashMap<>();
        putIfPresent(args, "path", reader.string("path"));
        putIfPresent(args, "to", reader.string("to"));
        putIfPresent(args, "from", reader.string("from"));
        putIfPresent(args, "file", reader.string("file"));
        putIfPresent(args, "content", reader.string("content"));
        putIfPresent(args, "encoding", reader.string("encoding"));
        putIfPresent(args, "charset", reader.string("charset"));
        putIfPresent(args, "dest", reader.string("dest"));
        if (reader.node().hasNonNull("paths") && reader.node().get("paths").isArray()) {
            java.util.List<String> paths = new java.util.ArrayList<>();
            reader.node().get("paths").forEach(item -> {
                if (item.isTextual()) {
                    paths.add(item.asText());
                }
            });
            if (!paths.isEmpty()) {
                args.put("paths", paths);
            }
        }
        if (reader.node().hasNonNull("offset") && reader.node().get("offset").canConvertToLong()) {
            args.put("offset", reader.node().get("offset").asLong());
        }
        if (reader.node().hasNonNull("length") && reader.node().get("length").canConvertToLong()) {
            args.put("length", reader.node().get("length").asLong());
        }
        return args;
    }

    /** GET 文件接口 path 等参数在 query，不能只读 body。 */
    private Map<String, Object> argsOf(PluginHttpRequest request) {
        Map<String, Object> args = argsOf(request.body());
        mergeQuery(args, request, "path");
        mergeQuery(args, request, "file");
        mergeQuery(args, request, "from");
        mergeQuery(args, request, "to");
        mergeQuery(args, request, "keyword");
        String offset = query(request, "offset");
        if (offset != null) {
            try {
                args.put("offset", Long.parseLong(offset));
            } catch (NumberFormatException ignored) {
                // 非法偏移交由节点侧报业务错误。
            }
        }
        String length = query(request, "length");
        if (length != null) {
            try {
                args.put("length", Long.parseLong(length));
            } catch (NumberFormatException ignored) {
                // 非法长度交由节点侧钳制。
            }
        }
        String page = query(request, "page");
        if (page != null) {
            try {
                args.put("page", Integer.parseInt(page));
            } catch (NumberFormatException ignored) {
                // 非法页码交由节点侧钳回 1。
            }
        }
        String size = query(request, "size");
        if (size != null) {
            try {
                args.put("size", Integer.parseInt(size));
            } catch (NumberFormatException ignored) {
                // 非法页大小交由节点侧按不分页处理。
            }
        }
        return args;
    }

    private static void mergeQuery(Map<String, Object> args, PluginHttpRequest request, String key) {
        String value = query(request, key);
        if (value != null) {
            args.put(key, value);
        }
    }

    public PluginHttpResponse ftpOpen(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, () -> {
            McpanelJson.MapReader body = McpanelJson.readMap(request.body());
            Integer ttl = null;
            if (body.node().hasNonNull("ttlMinutes") && body.node().get("ttlMinutes").canConvertToInt()) {
                ttl = body.node().get("ttlMinutes").asInt();
            }
            return PluginHttpResponse.ok(instances.ftpOpen(HttpGuards.actorOf(request), scope(request),
                    segment(request.path(), 3), ttl));
        });
    }

    public PluginHttpResponse ftpClose(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, () ->
                PluginHttpResponse.ok(instances.ftpClose(HttpGuards.actorOf(request), scope(request),
                        segment(request.path(), 3))));
    }

    /** 控制台实时输出：订阅节点 attach 泵（evt instance.output，300ms 聚合）。 */
    public PluginHttpResponse outputSubscribe(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, () ->
                PluginHttpResponse.ok(instances.outputSubscribe(HttpGuards.actorOf(request), scope(request),
                        segment(request.path(), 3))));
    }

    public PluginHttpResponse outputUnsubscribe(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, () ->
                PluginHttpResponse.ok(instances.outputUnsubscribe(HttpGuards.actorOf(request), scope(request),
                        segment(request.path(), 3))));
    }

    /** 控制台输出 SSE 流（evt instance.output，按 instanceId 过滤；attach 泵由池引用计数管理）。 */
    public PluginHttpResponse outputEvents(PluginHttpRequest request) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, () -> {
            String instanceId = segment(request.path(), 3);
            String nodeId = instances.nodeInstanceOf(scope(request), instanceId);
            PluginSseStream stream = outputPool.open(nodeId, instanceId);
            return new PluginHttpResponse(200,
                    Map.of("Cache-Control", "no-cache", "Connection", "keep-alive", "X-Accel-Buffering", "no"),
                    "text/event-stream", stream, false);
        });
    }

    private static void putIfPresent(Map<String, Object> args, String key, String value) {
        if (value != null) {
            args.put(key, value);
        }
    }

    private static String query(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0).trim();
    }

    private static int intQuery(PluginHttpRequest request, String key, int defaultValue) {
        String value = query(request, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return defaultValue;
        }
    }

    private static Integer intQueryOrNull(PluginHttpRequest request, String key) {
        String value = query(request, key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean booleanQuery(PluginHttpRequest request, String key) {
        return Boolean.parseBoolean(query(request, key));
    }

    /**
     * 从插件相对路径提取资源段。
     * 兼容：/admin/instances/{id}、mcpanel/admin/instances/{id}、/admin/instances/{id}/files。
     * 对 /admin/{collection}/{id}[/rest]：在 collection 之后取 id。
     */
    private static String segment(String path, int index) {
        return resourceAfter(path, "instances", "nodes");
    }

    private static String resourceAfter(String path, String... collections) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split("/");
        for (int i = 0; i < parts.length; i++) {
            for (String collection : collections) {
                if (collection.equals(parts[i]) && i + 1 < parts.length && !parts[i + 1].isBlank()) {
                    return java.net.URLDecoder.decode(parts[i + 1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    /** 从启动命令提取 -jar 后的核心文件名；缺省 server.jar。 */
    @SuppressWarnings("unchecked")
    private static String jarFromCommand(List<String> command) {
        if (command != null) {
            for (int i = 0; i < command.size() - 1; i++) {
                if ("-jar".equals(command.get(i))) {
                    String jar = command.get(i + 1);
                    if (jar != null && !jar.isBlank()) {
                        return jar.trim();
                    }
                }
            }
        }
        return "server.jar";
    }
}
