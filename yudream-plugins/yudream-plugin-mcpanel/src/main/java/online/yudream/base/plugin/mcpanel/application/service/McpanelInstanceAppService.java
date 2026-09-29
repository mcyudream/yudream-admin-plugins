package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import online.yudream.base.plugin.mcpanel.domain.service.InstancePolicy;
import online.yudream.base.plugin.mcpanel.domain.service.PortAllocator;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeAccess;
import online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException;
import online.yudream.base.plugin.mcpanel.infrastructure.sftp.SftpGatewayRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 实例用例（M2/M3）：CRUD、生命周期、控制台、文件与备份的节点代理；
 * 全部节点调用经 NodeControlPlane.call（caps 门禁），变更操作固定幂等键。
 */
public class McpanelInstanceAppService {

    /** CREATE/UPDATE 默认 15 分钟；输出/文件等短操作 45s，降低假超时。 */
    private static final long CALL_TIMEOUT_MS = 45_000L;
    /** instance.create/update 可能内联拉取镜像，给长超时（协议 §4：长操作应走任务，此处为 M2 简化）。 */
    private static final long CREATE_TIMEOUT_MS = 15L * 60 * 1000;
    /** 节点备份打包是同步 zip：大世界可能耗时数分钟，长超时避免 45s 假失败。 */
    private static final long BACKUP_TIMEOUT_MS = 10L * 60 * 1000;
    /** 备份受理状态的兜底有效期：面板重启后残留的受理记录最多保留 15 分钟。 */
    private static final long BACKUP_PENDING_TTL_MS = 15L * 60 * 1000;

    private final McpanelInstanceRepository instanceRepository;
    private final McpanelNodeRepository nodeRepository;
    private final PortAllocationRepository portRepository;
    private final NodeCallGateway nodeCalls;
    private final PortAllocator portAllocator;
    private final AuditRecorder audit;
    private final TenancyScope tenancy;
    private final MinecraftLinkService link;
    private final BackupCenter backupCenter;
    /** 备份保留策略存储（bootstrap attach；未挂接时按不限处理）。 */
    private volatile BackupPolicyStore backupPolicies;
    /** 事件触发型任务服务（bootstrap attach；未挂接时钩子整体旁路）。 */
    private volatile InstanceEventTaskService eventTasks;

    /** 停机侧回读对账的最小间隔：stats 快照 30s 一拍，限流放宽到一倍周期。 */
    private static final long READBACK_THROTTLE_MS = 60_000L;
    /** 每实例至多一个在途节点备份打包（受理 → 完成/失败），列表轮询消费。 */
    private final java.util.concurrent.ConcurrentHashMap<String, BackupCreation> pendingBackups =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 停机侧怀疑触发的回读限流（每节点 60s 一次），防 stats 快照周期打爆节点。 */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> suspectReadbackAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 挂接备份保留策略存储（bootstrap 装配期调用）。 */
    public void attachBackupPolicyStore(BackupPolicyStore store) {
        this.backupPolicies = store;
    }

    /** 挂接事件触发型任务服务（bootstrap 装配期调用）。 */
    public void attachEventTasks(InstanceEventTaskService eventTasks) {
        this.eventTasks = eventTasks;
    }

    public McpanelInstanceAppService(McpanelInstanceRepository instanceRepository,
                                     McpanelNodeRepository nodeRepository,
                                     PortAllocationRepository portRepository,
                                     NodeCallGateway nodeCalls,
                                     PortAllocator portAllocator,
                                     AuditRecorder audit,
                                     TenancyScope tenancy,
                                     MinecraftLinkService link,
                                     BackupCenter backupCenter) {
        this.instanceRepository = instanceRepository;
        this.nodeRepository = nodeRepository;
        this.portRepository = portRepository;
        this.nodeCalls = nodeCalls;
        this.portAllocator = portAllocator;
        this.audit = audit;
        this.tenancy = tenancy;
        this.link = link;
        this.backupCenter = backupCenter;
    }

    /**
     * 宿主备份中心端口：触发范围备份（targetCode 空=本机导出）与按范围查任务摘要。
     * 宿主 SPI 过旧时为 null，对应动作报「无通道」而非影响其他功能。
     */
    public interface BackupCenter {
        String trigger(String instanceId, String targetCode);

        java.util.List<java.util.Map<String, Object>> jobs(String scopeCode, int limit);
    }

    /** 一次受力的节点备份打包状态机：running → done/failed。 */
    static final class BackupCreation {
        final long startedAt = System.currentTimeMillis();
        volatile long finishedAt;
        volatile boolean failed;
        volatile String error = "";
        volatile String file = "";
    }

    /** 面板→节点调用包装：统一超时/错误映射，业务层不接触 CompletionException。 */
    public interface NodeCallGateway {
        CompletableFuture<Map<String, Object>> call(String nodeId, String method, Map<String, Object> payload);

        default CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                            Map<String, Object> payload, long timeoutMs) {
            return call(nodeId, method, payload);
        }
    }

    public interface AuditRecorder {
        void record(String actor, String action, String targetType, String targetId, String detail, String tenantId);
    }

    /** 租户数据范围（M2 起接入，未启用时全部放行）。 */
    public interface TenancyScope {
        boolean canAccess(String actorTenantContext, McpanelInstance instance);

        String tenantOf(Object requestContext, McpanelNode node);

        default List<String> visibleTenants(Object requestContext) {
            return List.of();
        }
    }

    public Map<String, Object> page(String scopeKey, int page, int size, String keyword, String status, String nodeId) {
        List<McpanelInstance> all = instanceRepository.findAll().stream()
                .filter(instance -> tenancy.canAccess(scopeKey, instance))
                .filter(instance -> nodeId == null || nodeId.isBlank() || instance.nodeId().equals(nodeId))
                .filter(instance -> status == null || status.isBlank()
                        || String.valueOf(instance.state()).equalsIgnoreCase(status))
                .filter(instance -> keyword == null || keyword.isBlank() || contains(instance.name(), keyword)
                || contains(instance.nodeId(), keyword) || contains(instance.remark(), keyword))
                .sorted((a, b) -> Long.compare(b.createdAt(), a.createdAt()))
                .toList();
        int p = Math.max(1, page);
        int s = size < 1 ? 10 : Math.min(size, 100);
        int from = Math.min((p - 1) * s, all.size());
        int to = Math.min(from + s, all.size());
        List<Map<String, Object>> records = all.subList(from, to).stream().map(this::toDto).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", all.size());
        result.put("page", p);
        result.put("size", s);
        return result;
    }

    public Map<String, Object> detail(String scopeKey, String id) {
        return toDto(findAccessible(scopeKey, id));
    }

    /** 供其它应用服务复用的范围校验实例读取（在线玩家探测等）。 */
    public McpanelInstance accessibleInstance(String scopeKey, String id) {
        return findAccessible(scopeKey, id);
    }

    /** 开启控制台实时输出推送（节点 attach 泵，300ms 聚合 evt）。 */
    public Map<String, Object> outputSubscribe(String actor, String scopeKey, String id) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        return call(node.id(), "instance.output.subscribe", Map.of("instanceId", id));
    }

    public Map<String, Object> outputUnsubscribe(String actor, String scopeKey, String id) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        return call(node.id(), "instance.output.unsubscribe", Map.of("instanceId", id));
    }

    /** 节点对外地址（P2P 候选主机补齐用）：广告地址覆盖 > 端点 host。 */
    public String nodeAdvertisedHost(String nodeId) {
        return directHostOf(requireNode(nodeId));
    }

    /** 节点能力查询（caps 由节点 hello 上报；P2P 信令据此如实报能力缺口）。 */
    public boolean nodeSupports(String nodeId, String capability) {
        McpanelNode node = requireNode(nodeId);
        return node.caps() != null && node.caps().contains(capability);
    }

    /** 通用节点调用（P2P 会话开面/候选中继/回收等按节点维度的方法）。 */
    public Map<String, Object> invokeNode(String nodeId, String method, Map<String, Object> payload) {
        return call(requireNode(nodeId).id(), method, payload);
    }

    /** 实例类型（PROXY protocol 目标文件按类型选择）。 */
    public String instanceKindOf(String scopeKey, String id) {
        return findAccessible(scopeKey, id).kind();
    }

    /** 实例所在节点 ID（SSE 端点解析用）。 */
    public String nodeInstanceOf(String scopeKey, String id) {
        return requireNode(findAccessible(scopeKey, id).nodeId()).id();
    }

    public Map<String, Object> create(String actor, String scopeKey, McpanelInstance spec) {
        return create(actor, scopeKey, spec, Map.of());
    }

    /**
     * nodePayloadExtras：追加到 instance.create 载荷的额外字段（如回收站找回的
     * fromTrash），仅作用于本次下发，不入库。
     */
    public Map<String, Object> create(String actor, String scopeKey, McpanelInstance spec,
                                      Map<String, Object> nodePayloadExtras) {
        InstancePolicy.validate(spec);
        String bindingProblem = link.bindingProblem(spec.mcServerId());
        if (bindingProblem != null) {
            throw McpanelBusinessException.invalid(bindingProblem);
        }
        McpanelNode node = nodeRepository.findById(spec.nodeId())
                .orElseThrow(() -> McpanelBusinessException.notFound("节点不存在"));
        if (!node.enabled() || !node.enrolled()) {
            throw new McpanelBusinessException("node-not-ready", 409, "节点未启用或未完成注册");
        }
        tenancy.tenantOf(scopeKey, node);
        if (instanceRepository.findAll().stream()
                .anyMatch(existing -> existing.nodeId().equals(spec.nodeId())
                        && existing.name().equalsIgnoreCase(spec.name()))) {
            throw new McpanelBusinessException("instance.name-conflict", 409, "同节点已存在同名实例");
        }
        List<McpanelInstance.PortMapping> ports = portAllocator.allocateFor(node, spec);
        long now = System.currentTimeMillis();
        McpanelInstance instance = new McpanelInstance(spec.id(), node.id(), spec.name(), spec.kind(),
                spec.mcVersion(), spec.templateKey(), spec.image(), spec.command(), spec.env(),
                spec.memoryMb(), spec.cpuMillis(), spec.diskMb(), ports, spec.config(),
                "installing", null, null, tenancy.tenantOf(scopeKey, node), spec.remark(),
                spec.domainSlug(), spec.domainEnabled(), spec.p2pEnabled(), spec.p2pWhitelist(),
                spec.nodeTrust(), spec.modpack(), spec.coreFallbackHistory(), spec.startDetect(),
                spec.autoRestart(), spec.autoStart(), now, now);
        instanceRepository.save(instance);
        for (McpanelInstance.PortMapping mapping : ports) {
            portRepository.allocate(node.id(), mapping.hostPort(), mapping.proto(), instance.id());
        }
        try {
            Map<String, Object> payload = withLinkEnv(instance.toNodePayload());
            if (nodePayloadExtras != null && !nodePayloadExtras.isEmpty()) {
                payload.putAll(nodePayloadExtras);
            }
            Map<String, Object> created = call(node.id(), "instance.create",
                    withIk(payload, "panel-create-" + instance.id()), CREATE_TIMEOUT_MS);
            // 节点回执状态原子落库（CAS）：与在途事件互不覆盖，删后不复活。
            String createdStateText = text(created.get("state"), "created");
            instanceRepository.mutateState(instance.id(),
                    current -> current.withState(createdStateText, current.lastExitCode(), System.currentTimeMillis()));
            McpanelInstance createdState = instanceRepository.findById(instance.id()).orElse(instance);
            audit.record(actor, "instance.create", "instance", instance.id(), instance.name(), instance.tenantId());
            maybeInstallCore(node.id(), createdState);
            maybeAssignDomain(actor, node, createdState);
            return toDto(createdState);
        } catch (RuntimeException error) {
            // 创建失败即回收端口并删除占位记录（幂等键保证节点侧不会迟到建容器）。
            for (McpanelInstance.PortMapping mapping : ports) {
                portRepository.release(node.id(), mapping.hostPort(), mapping.proto());
            }
            instanceRepository.delete(instance.id());
            throw error;
        }
    }

    /**
     * 创建成功后按 config.installUrl 下载核心（server.jar 等）。
     * 下载失败不回滚创建：实例已存在，可在文件页重试或手动上传。
     */
    private void maybeInstallCore(String nodeId, McpanelInstance instance) {
        Map<String, String> config = instance.config();
        if (config == null || config.isEmpty()) {
            return;
        }
        String url = String.valueOf(config.getOrDefault("installUrl", "")).trim();
        if (url.isBlank() || url.startsWith("null")) {
            return;
        }
        String auto = String.valueOf(config.getOrDefault("autoDownloadCore", "")).trim();
        if (!"true".equalsIgnoreCase(auto)) {
            return;
        }
        String fileName = String.valueOf(config.getOrDefault("installFileName", "")).trim();
        if (fileName.isBlank() || fileName.startsWith("null")) {
            fileName = jarFromCommand(instance.command());
        }
        if (fileName.isBlank()) {
            fileName = "server.jar";
        }
        // 注意：instance.config() 是不可变 Map（record Map.copyOf），不能 put；
        // 推导出的文件名只进入本次 install.run 的 payload（path），无需回写 config。
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("url", url);
        file.put("path", fileName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", instance.id());
        payload.put("files", List.of(file));
        payload.put("ik", "panel-install-" + instance.id() + "-" + System.currentTimeMillis());
        // 先落存根再下发：即使本次节点不可达，之后也能用同一份计划重试。
        persistInstallPlan(instance.id(), fileName, List.of(file));
        try {
            trackInstall(nodeId, instance.id(), fileName,
                    call(nodeId, "install.run", payload, CALL_TIMEOUT_MS));
        } catch (RuntimeException error) {
            audit.record("system", "instance.install.failed", "instance", instance.id(),
                    error.getMessage() == null ? "core download failed" : error.getMessage(),
                    instance.tenantId());
        }
    }

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

    public Map<String, Object> update(String actor, String scopeKey, String id, McpanelInstance spec) {
        McpanelInstance current = findAccessible(scopeKey, id);
        McpanelNode node = nodeRepository.findById(current.nodeId())
                .orElseThrow(() -> McpanelBusinessException.notFound("节点不存在"));
        InstancePolicy.validate(spec);
        if ("running".equalsIgnoreCase(current.state())) {
            throw new McpanelBusinessException("instance-running", 409, "请先停止实例再修改配置");
        }
        String bindingProblem = link.bindingProblem(spec.mcServerId());
        if (bindingProblem != null) {
            throw McpanelBusinessException.invalid(bindingProblem);
        }
        // 规格变更经 CAS 以最新文档为基：并发状态事件不会被旧读覆盖，
        // CAS 持续冲突（极端争用）显式报 409，而不是静默落一个过期快照。
        long stamp = System.currentTimeMillis();
        if (!instanceRepository.mutateState(id, base -> base.withSpec(spec, stamp))) {
            throw new McpanelBusinessException("instance.conflict", 409,
                    "实例状态正在变化，配置保存冲突，请稍后重试");
        }
        McpanelInstance updated = instanceRepository.findById(id).orElse(current);
        try {
            Map<String, Object> result = call(node.id(), "instance.update",
                    withIk(withLinkEnv(updated.toNodePayload()),
                            "panel-update-" + updated.id() + "-" + updated.updatedAt()), CREATE_TIMEOUT_MS);
            audit.record(actor, "instance.update", "instance", id, updated.name(), updated.tenantId());
            String nodeState = text(result.get("state"), null);
            if (nodeState != null) {
                instanceRepository.mutateState(id, fresh -> fresh.withState(nodeState, fresh.lastExitCode(),
                        System.currentTimeMillis()));
            }
            return toDto(instanceRepository.findById(id).orElse(updated));
        } catch (RuntimeException error) {
            // 回滚规格但保留最新运行时（state/lastExitCode 以 CAS 基线为准），不回写旧状态。
            instanceRepository.mutateState(id, fresh ->
                    current.withState(fresh.state(), fresh.lastExitCode(), fresh.updatedAt()));
            throw error;
        }
    }

    public Map<String, Object> action(String actor, String scopeKey, String id, String action, Integer timeoutSec) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        if (!List.of("start", "stop", "restart", "kill").contains(action)) {
            throw McpanelBusinessException.invalid("不支持的操作：" + action);
        }
        // 事件任务意图先行于 RPC：stop/restart/kill 抑制自动重启（退出事件可能先于 RPC 返回到达）；
        // start 复位自动重启的快速崩溃熔断（对标 MCSM 手动 open 清零计数）。
        if (eventTasks != null) {
            if ("start".equals(action)) {
                eventTasks.onManualStart(id);
            }
            else {
                eventTasks.markManualStop(id);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        if (timeoutSec != null) {
            payload.put("timeoutSec", timeoutSec);
        }
        Map<String, Object> result;
        try {
            result = call(node.id(), "instance." + action,
                    withIk(payload, "panel-" + action + "-" + id + "-" + System.currentTimeMillis()));
        } catch (RuntimeException error) {
            // Docker kill/stop 对已退出容器返回 409 not running：视为已停止，避免页面红错。
            String message = error.getMessage() == null ? "" : error.getMessage();
            boolean notRunning = message.contains("not running") || message.contains("is not running");
            if (("kill".equals(action) || "stop".equals(action)) && notRunning) {
                // CAS 落库：RPC 期间的节点事件不会与本结果互相覆盖。
                boolean applied = instanceRepository.mutateState(id,
                        current -> current.withState("exited", current.lastExitCode(), System.currentTimeMillis()));
                McpanelInstance exited = instanceRepository.findById(id)
                        .orElse(instance.withState("exited", instance.lastExitCode(), System.currentTimeMillis()));
                if (applied) {
                    audit.record(actor, "instance." + action, "instance", id,
                            instance.name() + "（容器已退出）", instance.tenantId());
                    link.writebackState(exited);
                }
                return toDto(exited);
            }
            throw error;
        }
        String targetState = text(result.get("state"), action.equals("start") ? "running" : "exited");
        Integer exitCode = intOrNull(result.get("exitCode"));
        // 状态写经 CAS（rev）：以存储最新文档为基，只迁移 state/lastExitCode，不整档覆盖。
        boolean applied = instanceRepository.mutateState(id,
                current -> current.withState(targetState, exitCode, System.currentTimeMillis()));
        McpanelInstance updated = instanceRepository.findById(id).orElse(
                instance.withState(targetState, exitCode, System.currentTimeMillis()));
        if (applied) {
            audit.record(actor, "instance." + action, "instance", id, instance.name(), instance.tenantId());
            link.writebackState(updated);
        }
        return toDto(updated);
    }

    /**
     * 事件触发型任务开关保存（对标 MCSM eventTask）：纯面板侧语义，不下发节点、
     * 与运行状态无关（运行中可随时切换），经 CAS 只迁移两个开关位。
     */
    public Map<String, Object> saveEventTask(String actor, String scopeKey, String id,
                                             boolean autoRestart, boolean autoStart) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        long stamp = System.currentTimeMillis();
        if (!instanceRepository.mutate(id, current -> current.withEventTask(autoRestart, autoStart, stamp))) {
            throw new McpanelBusinessException("instance.conflict", 409,
                    "实例状态正在变化，保存冲突，请稍后重试");
        }
        McpanelInstance updated = instanceRepository.findById(id).orElse(instance);
        audit.record(actor, "instance.event-task", "instance", id,
                instance.name() + "：自动重启=" + (autoRestart ? "开" : "关")
                        + "，自动启动=" + (autoStart ? "开" : "关"),
                instance.tenantId());
        return toDto(updated);
    }

    public Map<String, Object> delete(String actor, String scopeKey, String id, boolean purge) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        // 删除属面板操作：先标记手动停止，避免删除触发的容器退出被事件任务误判为意外退出。
        if (eventTasks != null) {
            eventTasks.markManualStop(id);
        }
        if (purge && !nodeSupports(node.id(), "instance.purge")) {
            throw new McpanelBusinessException("node.capability", 409,
                    "节点程序版本过低，不支持永久删除数据目录（instance.purge）；请升级节点端，或直接删除（数据将进入回收站保留期）。");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        Map<String, Object> nodeResult = call(node.id(), purge ? "instance.purge" : "instance.delete",
                withIk(payload, "panel-delete-" + id + "-" + (purge ? "purge" : "keep")));
        // 节点确认目录已进回收站（trashed=true）才落快照；老节点无该字段=目录原地
        // 保留（历史行为），不产生回收站记录。
        if (!purge && Boolean.TRUE.equals(nodeResult.get("trashed")) && trashSink != null) {
            try {
                trashSink.record(instance, actor);
            } catch (RuntimeException error) {
                // 快照落库失败不阻断删除：目录仍在节点回收站，可经节点文件管理清理。
                System.err.println("[mcpanel] 回收站快照写入失败: " + error.getMessage());
            }
        }
        for (PortAllocationRepository.Record record : portRepository.findByInstance(id)) {
            portRepository.release(record.nodeId(), record.port(), record.proto());
        }
        releaseDomainQuietly(actor, instance);
        instanceRepository.delete(id);
        cleanupInstanceSidecars(id);
        audit.record(actor, purge ? "instance.purge" : "instance.delete", "instance", id,
                instance.name() + (purge ? "（含数据）" : "（保留数据）"), instance.tenantId());
        // 回传终态：绑定子服的列表侧随即显示离线（实例记录已删，用终态快照回写）。
        link.writebackState(instance.withState("deleted", null, System.currentTimeMillis()));
        return Map.of("deleted", true, "purged", purge);
    }

    /**
     * 节点删除的级联清理（设计 §3.4）：删除节点前对其下全部实例执行
     * 保留数据的容器删除（instance.delete），随后释放端口、删除实例记录并回传终态。
     * 单实例失败不阻断整体：节点已离线时容器无从删除，仍完成面板侧清理，
     * 实例数据目录保留在节点机上（非破坏性，可人工恢复）。
     *
     * @return 级联清理的实例数
     */
    public int cascadeDeleteByNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return 0;
        }
        int cleaned = 0;
        for (McpanelInstance instance : instanceRepository.findAll()) {
            if (!nodeId.equals(instance.nodeId())) {
                continue;
            }
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("instanceId", instance.id());
                call(nodeId, "instance.delete",
                        withIk(payload, "panel-delete-" + instance.id() + "-keep"));
            } catch (RuntimeException error) {
                audit.record("system", "instance.node-cascade.failed", "instance", instance.id(),
                        (error.getMessage() == null ? "容器删除失败" : error.getMessage())
                                + "；面板记录继续清理，节点侧数据保留",
                        instance.tenantId());
            }
            for (PortAllocationRepository.Record record : portRepository.findByInstance(instance.id())) {
                portRepository.release(record.nodeId(), record.port(), record.proto());
            }
            releaseDomainQuietly("system", instance);
            instanceRepository.delete(instance.id());
            cleanupInstanceSidecars(instance.id());
            audit.record("system", "instance.node-cascade", "instance", instance.id(),
                    instance.name() + "（节点删除级联清理，保留节点侧数据）", instance.tenantId());
            link.writebackState(instance.withState("deleted", null, System.currentTimeMillis()));
            cleaned++;
        }
        return cleaned;
    }

    /**
     * 节点容器事件（evt instance.state）→ 原子落库 + 回传（由事件协调器驱动）。
     * 来源校验：事件必须来自实例所属节点（sourceNodeId 由连接管理器给出），
     * 防止跨节点事件错写；状态迁移经 CAS，删后不复活、旧事件不覆盖新写。
     *
     * @return true = 事件通过来源校验、实例存在且状态/退出码实际变化并已落库；
     *         false = 拒绝（实例缺失、跨节点伪造来源）或无实际变化。调用方可据此
     *         决定是否驱动下游联动（如计划任务事件），避免伪造/重复事件误触发。
     */
    public boolean onNodeInstanceEvent(String sourceNodeId, Map<String, Object> payload) {
        if (payload == null) {
            return false;
        }
        String instanceId = text(payload.get("instanceId"), null);
        if (instanceId == null || instanceId.isBlank()) {
            return false;
        }
        McpanelInstance instance = instanceRepository.findById(instanceId).orElse(null);
        if (instance == null) {
            return false;
        }
        if (sourceNodeId != null && !sourceNodeId.equals(instance.nodeId())) {
            // 事件声称的节点与实例归属不一致：拒绝应用（不静默改写其他节点的实例）。
            return false;
        }
        Object claimedNode = payload.get("nodeId");
        if (claimedNode != null && sourceNodeId != null
                && !sourceNodeId.equals(String.valueOf(claimedNode))) {
            return false;
        }
        String state = text(payload.get("state"), instance.state());
        // lastExitCode 只在事件显式携带时覆盖，避免运行中事件清掉退出码。
        boolean hasExitCode = payload.containsKey("lastExitCode");
        Integer exitCode = hasExitCode ? intOrNull(payload.get("lastExitCode")) : null;
        final boolean[] changed = {false};
        boolean applied = instanceRepository.mutateState(instanceId, current -> {
            Integer effectiveExit = hasExitCode ? exitCode : current.lastExitCode();
            if (String.valueOf(current.state()).equals(state)
                    && java.util.Objects.equals(current.lastExitCode(), effectiveExit)) {
                // 无变化：返回同引用短路，避免每条事件都产生一次文档写。
                return current;
            }
            changed[0] = true;
            return current.withState(state, effectiveExit, System.currentTimeMillis());
        });
        if (!applied || !changed[0]) {
            return false;
        }
        instanceRepository.findById(instanceId).ifPresent(link::writebackState);
        // 事件任务：未经面板操作的意外退出（容器 die）→ 自动重启（服务内自带手动意图与熔断门）。
        if (eventTasks != null && "exited".equals(state)) {
            eventTasks.onInstanceExited(instanceId);
        }
        return true;
    }

    /**
     * 节点上线恢复：以节点侧实时容器状态覆盖面板存量状态（面板/节点重启后状态过期的根治）。
     * 去重：同一回读内 instanceId 只处理一次（节点分页异常时防重复写）；
     * 来源校验：只回写仍归属本节点的实例；写入经 CAS，删后不复活。
     *
     * @return 实际发生状态变更的实例数
     */
    public int syncStatesFromNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return 0;
        }
        int updated = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int page = 1; page <= 10; page++) {
            Map<String, Object> result = call(nodeId, "instance.list", Map.of("page", page, "size", 100));
            if (!(result.get("records") instanceof List<?> records) || records.isEmpty()) {
                break;
            }
            for (Object item : records) {
                if (!(item instanceof Map<?, ?> record)) {
                    continue;
                }
                String instanceId = text(record.get("instanceId"), null);
                String state = text(record.get("state"), null);
                if (instanceId == null || state == null || state.isBlank()) {
                    continue;
                }
                if (!seen.add(instanceId)) {
                    continue;
                }
                McpanelInstance instance = instanceRepository.findById(instanceId).orElse(null);
                if (instance == null || !nodeId.equals(instance.nodeId())) {
                    continue;
                }
                if (state.equals(instance.state())) {
                    continue;
                }
                boolean applied = instanceRepository.mutateState(instanceId,
                        current -> current.withState(state, null, System.currentTimeMillis()));
                if (applied) {
                    instanceRepository.findById(instanceId).ifPresent(link::writebackState);
                    updated++;
                }
            }
            if (records.size() < 100) {
                break;
            }
        }
        return updated;
    }

    /**
     * 节点 stats 快照纠偏：docker events 断流重试 / 面板-节点 WS 断连期间，容器启停的
     * instance.state 事件会整段丢失且无补偿（上线回读只在重连时跑一次），面板 DB 可能
     * 与容器实际状态长期脱节（容器已 running 而面板仍 exited）。
     * stats 只上报运行系容器，因此只做「出现在快照里 → 以快照为准」的单向纠偏；
     * 不在快照中的实例不做任何推断（停机侧仍由 instance.state 事件 / 上线回读 / 离线
     * unknown 负责）。仅纠偏落库与联动回传，不驱动计划任务（事件语义由 instance.state 承载）。
     *
     * @return 实际发生状态变更的实例数
     */
    public int onNodeStatsSnapshot(String nodeId, Map<String, Object> stats) {
        if (nodeId == null || nodeId.isBlank() || stats == null) {
            return 0;
        }
        if (!(stats.get("containers") instanceof List<?> rows)) {
            return 0;
        }
        int updated = 0;
        // 停机侧纠偏：面板仍记 running、但在线节点的 stats 快照没有该容器 → instance.state
        // 事件大概率丢失（面板/节点一直在线时没有上线回读兜底，停机状态会长期滞留）。
        // 限流触发一次节点 instance.list 回读对账，由权威侧状态覆盖面板存量。
        if (!rows.isEmpty() && stopSideSuspected(nodeId, rows)) {
            updated += syncStatesFromNode(nodeId);
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> record)) {
                continue;
            }
            String instanceId = text(record.get("instanceId"), null);
            String state = normalizeStatsState(text(record.get("state"), null));
            if (instanceId == null || state == null) {
                continue;
            }
            McpanelInstance instance = instanceRepository.findById(instanceId).orElse(null);
            if (instance == null || !nodeId.equals(instance.nodeId())) {
                continue;
            }
            if (state.equals(instance.state())) {
                continue;
            }
            boolean applied = instanceRepository.mutateState(instanceId, current ->
                    state.equals(current.state())
                            ? current
                            : current.withState(state, current.lastExitCode(), System.currentTimeMillis()));
            if (applied) {
                instanceRepository.findById(instanceId).ifPresent(link::writebackState);
                updated++;
            }
        }
        return updated;
    }

    /** 停机侧怀疑判定：节点有实例 DB 记 running、但不在本次 stats 容器清单里；60s 限流内返回 false。 */
    private boolean stopSideSuspected(String nodeId, List<?> rows) {
        long now = System.currentTimeMillis();
        Long last = suspectReadbackAt.get(nodeId);
        if (last != null && now - last < READBACK_THROTTLE_MS) {
            return false;
        }
        java.util.Set<String> snapshotIds = new java.util.HashSet<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> record) {
                String id = text(record.get("instanceId"), null);
                if (id != null) {
                    snapshotIds.add(id);
                }
            }
        }
        boolean suspected = instanceRepository.findAll().stream()
                .anyMatch(instance -> nodeId.equals(instance.nodeId())
                        && "running".equalsIgnoreCase(String.valueOf(instance.state()))
                        && !snapshotIds.contains(instance.id()));
        if (suspected) {
            suspectReadbackAt.put(nodeId, now);
        }
        return suspected;
    }

    /**
     * stats 容器状态归一：与面板/前端状态词汇对齐（stopped 统一记 exited），unknown 不作纠偏依据。
     * starting = 节点 0.6.2+ 启动成功检测窗口（docker 已 running 但启动标记未命中）。
     * 包内可见：总览统计（OverviewService）的展示口径复用同一归一，避免语义漂移。
     */
    static String normalizeStatsState(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "running", "paused", "created", "exited", "starting" -> raw;
            case "stopped" -> "exited";
            default -> null;
        };
    }

    /** 节点离线 → 其下运行中实例置 unknown 并回传（设计 §3.4）；CAS 写，删后不复活。 */
    public void onNodeOffline(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return;
        }
        for (McpanelInstance instance : instanceRepository.findAll()) {
            if (!nodeId.equals(instance.nodeId())
                    || !"running".equalsIgnoreCase(String.valueOf(instance.state()))) {
                continue;
            }
            boolean applied = instanceRepository.mutateState(instance.id(), current -> {
                if (!"running".equalsIgnoreCase(String.valueOf(current.state()))) {
                    return current;
                }
                return current.withState("unknown", current.lastExitCode(), System.currentTimeMillis());
            });
            if (applied) {
                instanceRepository.findById(instance.id()).ifPresent(link::writebackState);
            }
        }
    }

    public Map<String, Object> command(String actor, String scopeKey, String id, String command) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        // 控制台（含计划任务命令通道）下发 stop/exit 等同手动停止：抑制事件任务的自动重启。
        String trimmed = command == null ? "" : command.trim().toLowerCase();
        if (eventTasks != null && ("stop".equals(trimmed) || "exit".equals(trimmed))) {
            eventTasks.markManualStop(id);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        payload.put("command", command);
        Map<String, Object> result = call(node.id(), "instance.command",
                withIk(payload, "panel-cmd-" + id + "-" + System.currentTimeMillis()));
        audit.record(actor, "instance.command", "instance", id, "控制台输入", instance.tenantId());
        return result;
    }

    /**
     * TPS 探测（Paper 系 paper/purpur/folia 支持 tps 命令）：前端周期调用，
     * 固定向控制台代发 tps 命令，输出经控制台流返回由前端解析展示。
     * 命令固定不接受前端注入；纯读探测且周期高频，不产生审计记录。
     */
    public Map<String, Object> tpsProbe(String scopeKey, String id) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        payload.put("command", "tps");
        return call(node.id(), "instance.command",
                withIk(payload, "panel-tps-" + id + "-" + System.currentTimeMillis()));
    }

    public Map<String, Object> output(String scopeKey, String id, String since, Integer tail) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        if (since != null && !since.isBlank()) {
            payload.put("since", since);
        }
        payload.put("tail", tail == null ? 200 : tail);
        try {
            return call(node.id(), "instance.output.read", payload);
        }
        catch (McpanelBusinessException error) {
            if (McpanelBusinessException.CODE_INSTANCE_NOT_FOUND.equals(error.code())) {
                // 实例在节点上尚无容器/数据（创建未完成、从未启动、被外部清空或安装重建中）：
                // 控制台历史按空处理，与文件列目录空态同一口径，不再把缺失报成 409。
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("text", "");
                empty.put("data", "");
                empty.put("nextCursor", "");
                empty.put("truncated", false);
                return empty;
            }
            throw error;
        }
    }

    // ---------- 文件与备份代理（M3） ----------

    public Map<String, Object> files(String scopeKey, String id, String operation, Map<String, Object> args) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        Map<String, Object> payload = new LinkedHashMap<>(args);
        payload.put("instanceId", id);
        String method = switch (operation) {
            case "list" -> "file.list";
            case "read" -> "file.read";
            case "write" -> "file.write";
            case "mkdir" -> "file.mkdir";
            case "rename" -> "file.rename";
            case "delete" -> "file.delete";
            case "download" -> "file.download.chunk";
            case "zip" -> "file.zip";
            case "unzip" -> "file.unzip";
            default -> throw McpanelBusinessException.invalid("不支持的文件操作：" + operation);
        };
        if ("write".equals(operation)) {
            FileCharsetCodec.applyCharset(payload);
        }
        if (!method.endsWith("list") && !method.endsWith("read") && !method.endsWith("chunk")) {
            payload.put("ik", "panel-file-" + id + "-" + operation + "-" + System.currentTimeMillis());
        }
        try {
            return call(node.id(), method, payload);
        }
        catch (McpanelBusinessException error) {
            if (McpanelBusinessException.CODE_INSTANCE_NOT_FOUND.equals(error.code())) {
                if ("list".equals(operation)) {
                    // 实例数据尚未就位（创建未完成/从未启动/被外部清空）：列目录按空目录处理，
                    // 由页面展示空态引导，不作为错误上报。
                    Map<String, Object> empty = new LinkedHashMap<>();
                    empty.put("entries", new java.util.ArrayList<>());
                    empty.put("total", 0);
                    return empty;
                }
                if ("read".equals(operation) || "download".equals(operation)) {
                    // 目标文件不存在（未生成、已被删除或安装重建中）：按 404 语义上报，
                    // 不再套用实例级 409 文案误导为「容器/数据被清空」。
                    throw new McpanelBusinessException("file.notFound", 404,
                            "目标不存在：文件或目录尚未生成，或已被删除（安装/重建期间会短暂出现）");
                }
            }
            throw error;
        }
    }

    /** 上传中断后回收节点临时分片的最长等待（有界，避免拖住原始异常上报）。 */
    private static final long UPLOAD_ABORT_TIMEOUT_MS = 10_000L;

    /** 需要审计的文件操作：变更类；list/read 与分块读取（编辑器路径）不审计。 */
    private static final java.util.Set<String> AUDITED_FILE_OPERATIONS = java.util.Set.of(
            "write", "mkdir", "rename", "delete", "zip", "unzip");

    /**
     * 小文件上传：走节点分块通道（begin/chunk/commit），面板侧不落盘。
     * 协议 §4：会话固定绑定实例——chunk/commit 均携带 instanceId，禁止凭 uploadId 跨实例访问；
     * 任一步失败 best-effort 有界 abort（10s）回收临时分片，并保留原始异常上抛。
     */
    public Map<String, Object> upload(String scopeKey, String id, String path, byte[] data, String sha256Hex) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        if (data == null || data.length > 96 * 1024 * 1024) {
            throw McpanelBusinessException.invalid("上传内容缺失或超过 96MiB");
        }
        Map<String, Object> begin = call(node.id(), "file.upload.begin", Map.of(
                "instanceId", id, "path", path, "size", data.length, "sha256", sha256Hex));
        String uploadId = String.valueOf(begin.get("uploadId"));
        if (uploadId == null || uploadId.isBlank() || "null".equals(uploadId)) {
            throw new McpanelBusinessException("upload.begin", 502, "节点未返回 uploadId，上传已取消");
        }
        try {
            int chunk = 96 * 1024;
            for (int offset = 0; offset < data.length; offset += chunk) {
                int end = Math.min(offset + chunk, data.length);
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("instanceId", id);
                part.put("uploadId", uploadId);
                part.put("offset", (long) offset);
                part.put("content", java.util.Base64.getEncoder().encodeToString(
                        java.util.Arrays.copyOfRange(data, offset, end)));
                call(node.id(), "file.upload.chunk", part);
            }
            return call(node.id(), "file.upload.commit",
                    Map.of("instanceId", id, "uploadId", uploadId));
        } catch (RuntimeException original) {
            // best-effort 有界清理：abort 失败只吞自己的异常，原始失败原因原样上抛。
            try {
                call(node.id(), "file.upload.abort",
                        Map.of("instanceId", id, "uploadId", uploadId), UPLOAD_ABORT_TIMEOUT_MS);
            } catch (RuntimeException ignored) {
                // 节点离线/不支持 abort 等场景：临时分片由节点会话过期清理兜底。
            }
            throw original;
        }
    }

    /**
     * 大文件分块上传底层通道（file.upload.begin/chunk/commit/abort），供上传任务服务
     * 把浏览器分片流式中转到节点；面板不落盘全量。会话固定绑定实例（同 upload）。
     */
    public Map<String, Object> uploadOp(String scopeKey, String instanceId, String method,
                                        Map<String, Object> payload) {
        McpanelInstance instance = findAccessible(scopeKey, instanceId);
        McpanelNode node = requireNode(instance.nodeId());
        Map<String, Object> frame = new LinkedHashMap<>(payload);
        frame.putIfAbsent("instanceId", instanceId);
        return call(node.id(), method, frame);
    }

    /**
     * 实例备份：create/list/restore/delete/trigger。
     * - create：节点同步 zip 耗时可能数分钟，改「受理 + 异步执行」——立即返回 accepted，
     *   完成状态经 list 轮询（pendingBackups），避免 45s/60s 超时假失败与无反馈；
     * - list：合并节点本机档（type=local，可恢复/删除）与宿主备份中心任务
     *   （type=export 本机导出 / offsite 异地推送，只读展示）；
     * - trigger：手动异地备份（经宿主备份中心，targetCode 必填）。
     */
    public Map<String, Object> backup(String actor, String scopeKey, String id, String operation, Map<String, Object> args) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        Map<String, Object> payload = new LinkedHashMap<>(args);
        payload.put("instanceId", id);
        switch (operation) {
            case "create" -> {
                return acceptBackupCreate(actor, id, node, payload);
            }
            case "list" -> {
                return backupList(id, node);
            }
            case "restore" -> {
                payload.put("ik", "panel-backup-" + id + "-restore-" + System.currentTimeMillis());
                Map<String, Object> restoreResult = call(node.id(), "backup.restore", payload, BACKUP_TIMEOUT_MS);
                audit.record(actor, "backup.restore", "instance", id,
                        String.valueOf(payload.get("file")), instance.tenantId());
                return restoreResult;
            }
            case "delete" -> {
                payload.put("ik", "panel-backup-" + id + "-delete-" + System.currentTimeMillis());
                Map<String, Object> deleteResult = call(node.id(), "backup.delete", payload);
                audit.record(actor, "backup.delete", "instance", id,
                        String.valueOf(payload.get("file")), instance.tenantId());
                return deleteResult;
            }
            case "trigger" -> {
                Object rawTarget = args == null ? null : args.get("targetCode");
                String targetCode = rawTarget == null ? "" : String.valueOf(rawTarget).trim();
                if (!targetCode.matches("[a-z0-9][a-z0-9-]{0,63}")) {
                    throw McpanelBusinessException.invalid("异地目标编码须为小写字母/数字/连字符（在宿主备份中心配置）");
                }
                if (backupCenter == null) {
                    throw new McpanelBusinessException("backup.no.channel", 509,
                            "宿主无备份触发通道（宿主或 SPI 过旧），无法发起异地备份");
                }
                String jobId = backupCenter.trigger(id, targetCode);
                audit.record(actor, "backup.trigger", "instance", id, "offsite:" + targetCode, instance.tenantId());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("jobId", jobId);
                result.put("accepted", true);
                return result;
            }
            default -> throw McpanelBusinessException.invalid("不支持的备份操作：" + operation);
        }
    }

    /** 受理一次节点本机备份：先占坑再异步执行，立即返回；同实例重复受理被拒。 */
    private Map<String, Object> acceptBackupCreate(String actor, String id, McpanelNode node,
                                                   Map<String, Object> payload) {
        sweepStaleBackups();
        BackupCreation existing = pendingBackups.get(id);
        if (existing != null && existing.finishedAt == 0) {
            throw new McpanelBusinessException("backup.running", 409, "已有备份任务在执行，请等待完成后再试");
        }
        payload.put("ik", "panel-backup-" + id + "-create-" + System.currentTimeMillis());
        BackupCreation creation = new BackupCreation();
        pendingBackups.put(id, creation);
        audit.record(actor, "backup.create", "instance", id, "node", node.id());
        nodeCalls.call(node.id(), "backup.create", payload, BACKUP_TIMEOUT_MS).whenComplete((result, error) -> {
            creation.finishedAt = System.currentTimeMillis();
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException completion
                        && completion.getCause() != null ? completion.getCause() : error;
                creation.failed = true;
                creation.error = cause.getMessage() == null ? "备份失败" : cause.getMessage();
            }
            else if (result != null && result.get("file") != null) {
                creation.file = String.valueOf(result.get("file"));
            }
            else {
                creation.failed = true;
                creation.error = "节点未返回备份结果";
            }
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        return result;
    }

    /** 备份列表：节点本机档 + 备份中心任务合并（按时间倒序），附在途打包状态。 */
    private Map<String, Object> backupList(String id, McpanelNode node) {
        sweepStaleBackups();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        try {
            Map<String, Object> result = call(node.id(), "backup.list", new LinkedHashMap<>(Map.of(
                    "instanceId", id)));
            Object backups = result.get("backups");
            String prefix = id + "-";
            if (backups instanceof List<?> items) {
                for (Object item : items) {
                    if (!(item instanceof Map<?, ?> backup)) {
                        continue;
                    }
                    String file = String.valueOf(backup.get("file"));
                    // 旧节点忽略 instanceId 过滤（全量返回）：面板按 {instanceId}- 前缀兜底过滤
                    if (!file.startsWith(prefix)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("type", "local");
                    row.put("file", file);
                    row.put("size", backup.get("size"));
                    row.put("at", backup.get("at"));
                    rows.add(row);
                }
            }
        } catch (RuntimeException ignored) {
            // 节点离线/旧版本：仍返回备份中心任务与受理状态，不让整页报错
        }
        if (backupCenter != null) {
            try {
                for (Map<String, Object> job : backupCenter.jobs("server-data", 50)) {
                    if (!id.equals(String.valueOf(job.get("instanceId")))) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    boolean offsite = !String.valueOf(job.getOrDefault("targetCode", "")).isBlank();
                    row.put("type", offsite ? "offsite" : "export");
                    row.put("targetCode", job.getOrDefault("targetCode", ""));
                    row.put("targetName", job.getOrDefault("targetName", ""));
                    row.put("jobId", job.getOrDefault("jobId", ""));
                    row.put("status", job.getOrDefault("status", ""));
                    row.put("percent", job.getOrDefault("percent", 0));
                    row.put("message", job.getOrDefault("message", ""));
                    row.put("archiveName", job.getOrDefault("archiveName", ""));
                    row.put("at", job.getOrDefault("createdAt", 0L));
                    rows.add(row);
                }
            } catch (RuntimeException ignored) {
                // 宿主任务查询失败不阻塞本机档展示
            }
        }
        rows.sort((a, b) -> Long.compare(number(b.get("at")), number(a.get("at"))));
        Map<String, Object> result = new LinkedHashMap<>();
        BackupCreation pending = pendingBackups.get(id);
        if (pending != null) {
            result.put("creating", pending.finishedAt == 0);
            if (pending.finishedAt > 0) {
                Map<String, Object> last = new LinkedHashMap<>();
                last.put("success", !pending.failed);
                last.put("error", pending.error);
                last.put("file", pending.file);
                last.put("finishedAt", pending.finishedAt);
                result.put("lastCreate", last);
            }
        }
        else {
            result.put("creating", false);
        }
        result.put("backups", rows);
        return result;
    }

    /** 清理过期受理记录：终态超过 TTL 的条目下次访问时移除。 */
    private void sweepStaleBackups() {
        long now = System.currentTimeMillis();
        pendingBackups.entrySet().removeIf(entry -> entry.getValue().finishedAt > 0
                && now - entry.getValue().finishedAt > BACKUP_PENDING_TTL_MS);
    }

    /** 实例备份保留策略视图。 */
    public Map<String, Object> backupPolicy(String scopeKey, String id) {
        findAccessible(scopeKey, id);
        BackupPolicyStore.Policy policy = backupPolicies != null
                ? backupPolicies.get(id)
                : new BackupPolicyStore.Policy(0, 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", id);
        result.put("keepCount", policy.keepCount());
        result.put("keepDays", policy.keepDays());
        return result;
    }

    /** 保存实例备份保留策略；节点可用时立即 prune 一次（best-effort，不阻塞保存）。 */
    public Map<String, Object> saveBackupPolicy(String actor, String scopeKey, String id,
                                                Object rawKeepCount, Object rawKeepDays) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        if (backupPolicies == null) {
            throw McpanelBusinessException.invalid("备份策略存储未就绪");
        }
        BackupPolicyStore.Policy policy = backupPolicies.save(id, intValue(rawKeepCount), intValue(rawKeepDays));
        audit.record(actor, "backup.policy", "instance", id,
                "keepCount=" + policy.keepCount() + ",keepDays=" + policy.keepDays(), instance.tenantId());
        try {
            McpanelNode node = requireNode(instance.nodeId());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("instanceId", id);
            payload.put("keepCount", policy.keepCount());
            payload.put("keepDays", policy.keepDays());
            call(node.id(), "backup.prune", payload, CALL_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
            // 节点离线/旧版本：策略已保存，下次备份打包时同样会按策略清理
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", id);
        result.put("keepCount", policy.keepCount());
        result.put("keepDays", policy.keepDays());
        return result;
    }

    private static int intValue(Object value) {
        if (value instanceof Number num) {
            return num.intValue();
        }
        try {
            return (int) Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static long number(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    public Map<String, Object> imagePull(String scopeKey, String nodeId, String image) {
        requireNode(nodeId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("image", image);
        payload.put("ik", "panel-image-pull-" + nodeId + "-" + image.hashCode() + "-" + System.currentTimeMillis());
        return call(nodeId, "image.pull", payload);
    }

    /** 节点本机 Docker 镜像列表（MCSM 镜像管理）。 */
    public Map<String, Object> imageList(String scopeKey, String nodeId) {
        requireNode(nodeId);
        return call(nodeId, "image.list", Map.of());
    }

    public Map<String, Object> imageRemove(String scopeKey, String nodeId, String image, boolean force) {
        requireNode(nodeId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("image", image);
        payload.put("force", force);
        payload.put("ik", "panel-image-remove-" + nodeId + "-" + image.hashCode() + "-" + System.currentTimeMillis());
        return call(nodeId, "image.remove", payload);
    }

    /** SFTP 网关可选挂载：未挂载或配置关闭时 ftp.open 维持节点直连形态。 */
    public record SftpGatewayHandle(SftpGatewayRegistry registry,
                                    Supplier<PanelSettings.SftpGateway> config) {
    }

    private volatile SftpGatewayHandle sftpGateway;

    /** bootstrap 在 onEnable 装配（注册表 + 设置读取），不挂载 = 网关能力整体下线。 */
    public void attachSftpGateway(SftpGatewayHandle handle) {
        this.sftpGateway = handle;
    }

    /** 核心安装任务跟踪（进度轮询 + DTO 装饰）；bootstrap 装配，可空。 */
    private volatile InstallTaskTracker installTracker;

    /** 安装计划存根（重试安装的重建来源）；bootstrap 装配，可空。 */
    private volatile InstallPlanStore installPlanStore;

    /** 计划条目源回退补全（MR/CF 换源）；bootstrap 装配，可空。 */
    private volatile java.util.function.UnaryOperator<Map<String, Object>> planSourceEnricher;

    /** 计划条目源回退补全（MR/CF 换源）：bootstrap 装配 ModpackService::enrichPlanItem。 */
    public void attachPlanSourceEnricher(java.util.function.UnaryOperator<Map<String, Object>> enricher) {
        this.planSourceEnricher = enricher;
    }

    /** 实例性能历史环形窗口；bootstrap 装配，可空。 */
    private volatile MetricsHistoryService metricsHistory;

    /** 子服软链接同步；bootstrap 装配，可空。 */
    private volatile SyncLinkService syncLinks;

    /** 代理纳管组（删除代理实例时清理组文档）；bootstrap 装配，可空。 */
    private volatile ProxyGroupService proxyGroups;

    /** 实例域名自动解析（创建时分配、删除时释放）；bootstrap 装配，可空。 */
    private volatile DomainService domains;

    /** 回收站快照口（删除且节点确认 trashed 后回调）；bootstrap 装配，可空。 */
    private volatile TrashSink trashSink;

    /** 回收站快照回调：实例删除且节点确认目录已移入回收站时触发（bootstrap 装配到 TrashAppService）。 */
    public interface TrashSink {
        void record(McpanelInstance instance, String deletedBy);
    }

    public void attachTrashSink(TrashSink sink) {
        this.trashSink = sink;
    }

    public void attachInstallTracker(InstallTaskTracker tracker) {
        this.installTracker = tracker;
    }

    public void attachInstallPlanStore(InstallPlanStore store) {
        this.installPlanStore = store;
    }

    public void attachMetricsHistory(MetricsHistoryService history) {
        this.metricsHistory = history;
    }

    public void attachSidecars(SyncLinkService syncLinks, ProxyGroupService proxyGroups) {
        this.syncLinks = syncLinks;
        this.proxyGroups = proxyGroups;
    }

    /** 域名服务装配（其 InstancePort 由本服务实现：实例读写都收敛在应用服务层）。 */
    public void attachDomains(DomainService domainService) {
        this.domains = domainService;
    }

    /** 单端口入口（mc-router）配置读取口；bootstrap 装配，未装配时入口模式视为未配置。 */
    private volatile Supplier<PanelSettings.Entry> entryConfig;

    public void attachEntryConfig(Supplier<PanelSettings.Entry> supplier) {
        this.entryConfig = supplier;
    }

    /** 当前入口配置（未装配/未配置时为 null）。 */
    PanelSettings.Entry entryConfigOrNull() {
        Supplier<PanelSettings.Entry> supplier = entryConfig;
        return supplier == null ? null : supplier.get();
    }

    /** 单端口入口（mc-router）路由同步；bootstrap 装配，可空（未装配 = 不做路由下发）。 */
    private volatile EntryRouteService entryRoutes;

    public void attachEntryRoutes(EntryRouteService routes) {
        this.entryRoutes = routes;
    }

    /** PROXY protocol 与接入方式的同步（入口模式自动开、其它模式自动关）；bootstrap 装配，可空。 */
    private volatile ProxyProtocolSyncService proxyProtocolSync;

    public void attachProxyProtocolSync(ProxyProtocolSyncService sync) {
        this.proxyProtocolSync = sync;
    }

    // ---------- 域名自动解析（DomainService.InstancePort 的实现 + 编排） ----------

    /** 供 DomainService 读取实例清单（slug 冲突检测）。 */
    public List<McpanelInstance> allInstances() {
        return instanceRepository.findAll();
    }

    /** 供 DomainService 持久化分配状态（CAS 原子写，不触碰容器）。 */
    public void setInstanceDomain(String instanceId, String slug, boolean enabled) {
        instanceRepository.mutate(instanceId, instance -> instance.withDomain(slug, enabled, System.currentTimeMillis()));
    }

    /**
     * 实例域名的解析目标：按「节点接入方式」取值。
     * - direct：节点直连地址（广告地址 sftpHost 覆盖 > 端点 host）；
     * - manual / frp：节点上填写的解析地址（IP → A/AAAA，主机名 → CNAME）；
     * - p2p：打洞接入不写公网解析（返回带说明的阻塞目标）。
     */
    public DomainService.Target domainTargetOf(String nodeId) {
        McpanelNode node = requireNode(nodeId);
        return domainTargetFor(node, directHostOf(node), entryConfigOrNull());
    }

    /**
     * 纯函数形态的接入方式 → 解析目标换算（便于单测；节点/直连地址/入口配置由调用方提供）：
     * direct 用节点直连地址；manual/frp 用节点上填的地址；entry 用入口地址（A 指向入口，
     * 默认端口 25565 免 SRV，非默认端口写 SRV 指向入口端口）；p2p 不写公网解析。
     */
    static DomainService.Target domainTargetFor(McpanelNode node, String directHost,
                                                PanelSettings.Entry entry) {
        String mode = NodeAccess.normalizeMode(node.accessMode());
        String label = NodeAccess.label(mode);
        if (NodeAccess.MODE_P2P.equals(mode)) {
            return DomainService.Target.blocked(mode, label,
                    "该节点为启动器打洞接入：实例不开放公网映射，不写公网解析，玩家经启动器连接");
        }
        if (NodeAccess.MODE_ENTRY.equals(mode)) {
            String entryHost = entry == null || entry.host() == null ? "" : entry.host().trim();
            if (entryHost.isEmpty()) {
                return DomainService.Target.blocked(mode, label,
                        "未配置单端口入口地址（面板设置 → 单端口入口）：入口模式下 A 记录指向入口，"
                                + "玩家用域名连接、由入口按域名转发到实例");
            }
            int entryPort = entry.port() <= 0 ? 25565 : entry.port();
            String entryType = NodeAccess.recordTypeOf(entryHost);
            // 25565 免 SRV；非默认端口写 SRV 指向 fqdn（fqdn 的 A 已指向入口，入口换 IP 只改一处）。
            return new DomainService.Target(mode, label, entryType, entryHost, null,
                    entryPort == 25565 ? 0 : entryPort, null);
        }
        boolean direct = NodeAccess.MODE_DIRECT.equals(mode);
        String host = direct ? (directHost == null ? "" : directHost.trim()) : node.accessTarget();
        if (host.isBlank()) {
            return DomainService.Target.blocked(mode, label, direct
                    ? "节点未配置对外地址（节点端点/广告地址），无法写入解析记录"
                    : label + " 未在节点上填写解析地址");
        }
        String recordType = NodeAccess.recordTypeOf(host);
        // CNAME 时 SRV 目标必须指真实主机（RFC 2181：SRV 目标不得是别名）。
        String srvTarget = "CNAME".equals(recordType) ? host : null;
        return new DomainService.Target(mode, label, recordType, host, srvTarget, null);
    }

    /** 节点直连地址（入口路由的后端兜底地址同样用它）：广告地址（sftpHost）覆盖 > 端点 host。 */
    public String directHostOf(McpanelNode node) {
        String advertised = node.sftpHost();
        if (advertised != null && !advertised.isBlank()) {
            return advertised.trim();
        }
        return ProxyGroupService.hostOfEndpoint(node.endpoint());
    }

    public Map<String, Object> domainView(String scopeKey, String id) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        DomainService service = domains;
        DomainService.Target target = domainTargetOf(instance.nodeId());
        Map<String, Object> view = service == null
                ? new LinkedHashMap<>(Map.of("available", false, "reason", "面板未装配域名服务"))
                : service.view(instance, target);
        view.put("address", target.recordValue() == null ? "" : target.recordValue());
        view.put("nodeAccessMode", target.mode());
        view.put("nodeAccessLabel", target.modeLabel());
        if (NodeAccess.MODE_ENTRY.equals(target.mode())) {
            PanelSettings.Entry entry = entryConfigOrNull();
            boolean apiReady = entry != null && entry.apiBase() != null && !entry.apiBase().isBlank();
            view.put("entryApiReady", apiReady);
            view.put("entryHint", apiReady
                    ? "入口（mc-router）已配置：分配域名时面板会把该域名推送到入口路由表"
                    : "尚未配置入口 API 地址（面板设置 → 单端口入口）：A 记录会写入，但入口收不到路由，玩家连不上");
        }
        return view;
    }

    /** 分配/重新同步域名（幂等）：按节点接入方式写入记录，Java 版再写 SRV；入口模式再推路由。 */
    public Map<String, Object> domainAssign(String actor, String scopeKey, String id, boolean srv) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        Map<String, Object> result = new LinkedHashMap<>(
                requireDomains().assign(actor, instance, domainTargetOf(instance.nodeId()), srv));
        // 入口模式：实例侧必须允许 PROXY protocol，否则玩家 IP 会显示为入口机地址；
        // 非入口模式：保持关闭（开着会让直连玩家被拒绝）——失败只提示，不回滚解析写入。
        ProxyProtocolSyncService sync = proxyProtocolSync;
        if (sync != null && NodeAccess.MODE_ENTRY.equals(domainTargetOf(instance.nodeId()).mode())) {
            try {
                Map<String, Object> synced = sync.syncForInstance(actor, instance, true);
                if (!Boolean.TRUE.equals(synced.get("supported"))) {
                    result.put("proxyProtocolNote", String.valueOf(synced.get("reason")));
                }
            }
            catch (RuntimeException error) {
                result.put("proxyProtocolNote", "PROXY protocol 未自动开启（可在域名页一键开启）：" + error.getMessage());
            }
        }
        EntryRouteService routes = entryRoutes;
        if (routes != null && routes.configured()) {
            try {
                Map<String, Object> published = routes.publish(instance);
                result.putAll(published);
                if (Boolean.FALSE.equals(published.get("published"))) {
                    result.put("entryNote", String.valueOf(published.get("reason")));
                }
            }
            catch (RuntimeException error) {
                // 路由推送失败不回滚解析记录：面板会按 1 分钟对账自动补上。
                result.put("entryNote", "解析已写入，但入口路由推送失败（将自动重试）：" + error.getMessage());
                audit.record(actor, "entry.routes.publish.failed", "instance", instance.id(),
                        instance.name() + "：" + error.getMessage(), instance.tenantId());
            }
        }
        return result;
    }

    public Map<String, Object> domainRelease(String actor, String scopeKey, String id) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        Map<String, Object> result = new LinkedHashMap<>(requireDomains().release(actor, instance));
        EntryRouteService routes = entryRoutes;
        if (routes != null) {
            try {
                routes.revoke(instance);
            }
            catch (RuntimeException error) {
                audit.record(actor, "entry.routes.revoke.failed", "instance", instance.id(),
                        instance.name() + "：" + error.getMessage(), instance.tenantId());
            }
        }
        return result;
    }

    public Map<String, Object> domainVerify(String scopeKey, String id, boolean srv) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        return requireDomains().verify(instance, domainTargetOf(instance.nodeId()), srv);
    }

    private DomainService requireDomains() {
        DomainService service = domains;
        if (service == null) {
            throw new McpanelBusinessException("domain.disabled", 409, "面板未装配域名服务");
        }
        return service;
    }

    /** 创建成功后按需自动分配域名：失败不回滚实例，管理员可在域名页重试。 */
    private void maybeAssignDomain(String actor, McpanelNode node, McpanelInstance instance) {
        DomainService service = domains;
        if (service == null || !instance.domainEnabled() || !service.enabled()) {
            return;
        }
        try {
            DomainService.Target target = domainTargetOf(node.id());
            if (!target.assignable()) {
                audit.record(actor, "instance.domain.assign.failed", "instance", instance.id(),
                        instance.name() + "：" + target.blockReason(), instance.tenantId());
                return;
            }
            service.assign(actor, instance, target, javaSrv(instance));
        }
        catch (RuntimeException error) {
            audit.record(actor, "instance.domain.assign.failed", "instance", instance.id(),
                    instance.name() + "：" + (error.getMessage() == null ? "域名分配失败" : error.getMessage()),
                    instance.tenantId());
        }
    }

    /** SRV 仅对 Java 版有意义（基岩版走 UDP，无 SRV）。 */
    static boolean javaSrv(McpanelInstance instance) {
        return !"bedrock".equalsIgnoreCase(instance.kind());
    }

    /** 删除实例前的域名释放（尽力而为：云解析/入口不可用时不影响删除本身）。 */
    private void releaseDomainQuietly(String actor, McpanelInstance instance) {
        EntryRouteService routes = entryRoutes;
        if (routes != null) {
            try {
                routes.revoke(instance);
            }
            catch (RuntimeException ignored) {
                // 对账会兜底清理
            }
        }
        DomainService service = domains;
        if (service != null) {
            service.releaseQuietly(actor, instance);
        }
    }

    private void trackInstall(String nodeId, String instanceId, String fileName, Map<String, Object> receipt) {
        InstallTaskTracker tracker = installTracker;
        if (tracker == null || receipt == null) {
            return;
        }
        Object taskId = receipt.get("taskId");
        if (taskId != null && !String.valueOf(taskId).isBlank()) {
            tracker.begin(nodeId, instanceId, String.valueOf(taskId), fileName);
        }
    }

    private void cleanupInstanceSidecars(String instanceId) {
        if (installTracker != null) {
            installTracker.drop(instanceId);
        }
        if (installPlanStore != null) {
            installPlanStore.delete(instanceId);
        }
        if (metricsHistory != null) {
            metricsHistory.evict(instanceId);
        }
        if (syncLinks != null) {
            syncLinks.evict(instanceId);
        }
        if (proxyGroups != null) {
            proxyGroups.evictGroup(instanceId);
        }
    }

    /** 开通实例临时 SFTP（节点 ftp.open：随机账号密码，TTL 自动关）。 */
    public Map<String, Object> ftpOpen(String actor, String scopeKey, String id, Integer ttlMinutes) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        payload.put("ttlMinutes", ttlMinutes == null ? 120 : ttlMinutes);
        payload.put("ik", "panel-ftp-open-" + id + "-" + System.currentTimeMillis());
        Map<String, Object> result = call(node.id(), "ftp.open", payload);
        audit.record(actor, "ftp.open", "instance", id, instance.name(), instance.tenantId());
        SftpGatewayHandle gateway = sftpGateway;
        if (gateway == null) {
            Map<String, Object> response = new LinkedHashMap<>(result);
            response.putIfAbsent("host", node.advertisedSftpHost());
            return response;
        }
        PanelSettings.SftpGateway config = gateway.config().get();
        if (config == null || !config.enabled() || config.port() < 1) {
            Map<String, Object> response = new LinkedHashMap<>(result);
            response.putIfAbsent("host", node.advertisedSftpHost());
            return response;
        }
        return gatewayResponse(gateway.registry(), config, instance, node, result, ttlMinutes);
    }

    /** 网关模式：不下发节点侧凭据，改发 mc-短别名 + 网关密码，节点地址对客户端透明。 */
    private Map<String, Object> gatewayResponse(SftpGatewayRegistry registry, PanelSettings.SftpGateway config,
                                                McpanelInstance instance, McpanelNode node,
                                                Map<String, Object> nodeResult, Integer ttlMinutes) {
        long expiresAt = numberValue(nodeResult.get("expiresAt"));
        if (expiresAt <= 0) {
            long ttl = ttlMinutes == null ? 120 : Math.max(1, ttlMinutes);
            expiresAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(ttl);
        }
        SftpGatewayRegistry.Minted minted = registry.mint(instance.id(), instance.name(), node.id(), node.name(),
                dialHostOf(node), (int) numberValue(nodeResult.get("port")),
                textValue(nodeResult.get("user")), textValue(nodeResult.get("password")), expiresAt);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("gateway", true);
        response.put("host", config.advertisedHost() == null ? "" : config.advertisedHost().trim());
        response.put("port", config.port());
        response.put("user", minted.username());
        response.put("password", minted.password());
        response.put("expiresAt", expiresAt);
        response.put("instanceId", instance.id());
        response.put("instanceName", instance.name());
        response.put("nodeId", node.id());
        response.put("nodeName", node.name());
        return response;
    }

    public Map<String, Object> ftpClose(String actor, String scopeKey, String id) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        payload.put("ik", "panel-ftp-close-" + id + "-" + System.currentTimeMillis());
        Map<String, Object> result = call(node.id(), "ftp.close", payload);
        audit.record(actor, "ftp.close", "instance", id, instance.name(), instance.tenantId());
        SftpGatewayHandle gateway = sftpGateway;
        if (gateway != null) {
            gateway.registry().dropByInstance(id);
        }
        return result;
    }

    /** 节点本机全部 Docker 容器（对标 MCSM 远程主机容器列表）。 */
    public Map<String, Object> nodeContainers(String scopeKey, String nodeId) {
        requireNode(nodeId);
        return call(nodeId, "node.containers.list", Map.of());
    }

    public Map<String, Object> task(String scopeKey, String nodeId, String taskId) {
        requireNode(nodeId);
        return call(nodeId, "task.get", Map.of("taskId", taskId));
    }

    public Map<String, Object> install(String scopeKey, String id, List<Map<String, Object>> plan) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        // MR/CF 换源：把官方 CDN 直链展开为源回退候选（幂等；未命中已知主机的条目原样保留）。
        java.util.function.UnaryOperator<Map<String, Object>> enricher = planSourceEnricher;
        List<Map<String, Object>> enriched = enricher == null ? plan
                : plan.stream().map(enricher).toList();
        String fileName = enriched.size() == 1 ? String.valueOf(enriched.get(0).get("path"))
                : enriched.size() + " 个文件";
        persistInstallPlan(id, fileName, enriched);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        payload.put("files", enriched);
        Map<String, Object> receipt = call(node.id(), "install.run", payload);
        trackInstall(node.id(), id, fileName, receipt);
        return receipt;
    }

    /**
     * 重试安装：按最近一次落库的安装计划原样重建 install.run（失败/卡死恢复入口）。
     * 无存根时说明该实例未经历过自动安装（或存根已随删除清理），引导重新上传整合包。
     */
    public Map<String, Object> installRetry(String actor, String scopeKey, String id) {
        McpanelInstance instance = findAccessible(scopeKey, id);
        McpanelNode node = requireNode(instance.nodeId());
        InstallTaskTracker tracker = installTracker;
        if (tracker != null && tracker.hasRunning(id)) {
            throw new IllegalStateException("已有安装任务进行中，请等待其结束");
        }
        InstallPlanStore store = installPlanStore;
        InstallPlanStore.StoredPlan stored = store == null ? null : store.find(id).orElse(null);
        if (stored == null || stored.files().isEmpty()) {
            throw new IllegalArgumentException("没有可重试的安装计划：整合包实例请重新上传整合包应用，其他实例可在文件页手动上传服务端核心");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        payload.put("files", stored.files());
        payload.put("ik", "panel-install-retry-" + id + "-" + System.currentTimeMillis());
        Map<String, Object> receipt = call(node.id(), "install.run", payload, CALL_TIMEOUT_MS);
        trackInstall(node.id(), id, stored.fileName(), receipt);
        audit.record(actor, "instance.install.retry", "instance", id, stored.fileName(), instance.tenantId());
        return receipt;
    }

    /** 安装计划落存根（重试来源）；尽力而为，存储失败不阻断安装。 */
    private void persistInstallPlan(String instanceId, String fileName, List<Map<String, Object>> plan) {
        InstallPlanStore store = installPlanStore;
        if (store == null) {
            return;
        }
        try {
            store.save(instanceId, fileName, plan);
        }
        catch (RuntimeException ignored) {
            // 存根失败不影响主流程
        }
    }

    // ---------- 内部 ----------

    /** 节点控制端点的 host 部分（面板每次拨号都在验证其可达性），作为网关回源拨号目标。 */
    private static String dialHostOf(McpanelNode node) {
        String endpoint = node.endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        String value = endpoint.trim().replace("wss://", "").replace("ws://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.indexOf(':');
        return colon > 0 ? value.substring(0, colon) : value;
    }

    private static long numberValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String textValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private McpanelInstance findAccessible(String scopeKey, String id) {
        McpanelInstance instance = instanceRepository.findById(id)
                .orElseThrow(() -> McpanelBusinessException.notFound("实例不存在"));
        if (!tenancy.canAccess(scopeKey, instance)) {
            throw McpanelBusinessException.notFound("实例不存在");
        }
        return instance;
    }

    private McpanelNode requireNode(String nodeId) {
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> McpanelBusinessException.notFound("节点不存在"));
    }

    private Map<String, Object> call(String nodeId, String method, Map<String, Object> payload) {
        return call(nodeId, method, payload, CALL_TIMEOUT_MS);
    }

    private Map<String, Object> call(String nodeId, String method, Map<String, Object> payload, long timeoutMs) {
        CompletableFuture<Map<String, Object>> future = nodeCalls.call(nodeId, method, payload, timeoutMs);
        try {
            return future.get(timeoutMs + 5_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new McpanelBusinessException("internal.error", 500, "节点调用被中断");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof NodeCallException nodeCall) {
                String code = nodeCall.code() == null ? "node.error" : nodeCall.code();
                String message = nodeCall.getMessage() == null ? "" : nodeCall.getMessage();
                if (McpanelBusinessException.CODE_INSTANCE_NOT_FOUND.equals(code)
                        || message.contains(McpanelBusinessException.CODE_INSTANCE_NOT_FOUND)) {
                    throw new McpanelBusinessException(code, 409,
                            "instance.notFound：节点上不存在该实例的容器/数据。可能创建未完成、外部删除或数据卷被清空。可启动一次重建，或删除后重新创建。");
                }
                // 节点的业务拒绝（实例运行中/未运行等）：透传人话 + 409，而不是 500 级技术错误。
                if (message.contains("请先停止实例")) {
                    throw new McpanelBusinessException("instance.running", 409,
                            "实例正在运行中：该操作需要先停止实例。请到控制台点击「停止」，稍候再试。");
                }
                if (message.contains("已在运行")) {
                    throw new McpanelBusinessException("instance.running", 409,
                            "实例已经在运行中，无需重复启动。");
                }
                if ("node.timeout".equals(code)) {
                    throw new McpanelBusinessException(code, 504,
                            "节点响应超时：" + method + "（请检查节点负载或 Docker）");
                }
                throw new McpanelBusinessException(code, code.startsWith("node.") ? 409 : 500, message);
            }
            if (cause instanceof TimeoutException) {
                throw new McpanelBusinessException("node.timeout", 504, "节点响应超时：" + method);
            }
            throw new McpanelBusinessException("node.unreachable", 502, "节点调用失败：" + cause.getMessage());
        } catch (TimeoutException error) {
            throw new McpanelBusinessException("node.timeout", 504,
                    "节点响应超时：" + method + "（请检查节点负载或 Docker）");
        }
    }

    static Map<String, Object> withIk(Map<String, Object> payload, String ik) {
        Map<String, Object> with = new LinkedHashMap<>(payload);
        with.put("ik", ik);
        return with;
    }

    /** 注入联动：绑定了子服的实例在创建/改配载荷里合并 MCSERVER_ID / MCSERVER_TERM。 */
    private Map<String, Object> withLinkEnv(Map<String, Object> payload) {
        Object serverId = payload.get("instanceId");
        McpanelInstance instance = instanceRepository.findById(String.valueOf(serverId)).orElse(null);
        if (instance == null || instance.mcServerId() == null || instance.mcServerId().isBlank()) {
            return payload;
        }
        Map<String, String> merged = new LinkedHashMap<>(instance.env());
        link.injectEnv(instance.mcServerId()).forEach(merged::putIfAbsent);
        Map<String, Object> with = new LinkedHashMap<>(payload);
        with.put("env", merged);
        return with;
    }

    private Map<String, Object> toDto(McpanelInstance instance) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", instance.id());
        dto.put("nodeId", instance.nodeId());
        dto.put("name", instance.name());
        dto.put("kind", instance.kind());
        dto.put("mcVersion", instance.mcVersion());
        dto.put("templateKey", instance.templateKey());
        dto.put("image", instance.image());
        dto.put("command", instance.command());
        dto.put("env", instance.env());
        dto.put("memoryMb", instance.memoryMb());
        dto.put("cpuMillis", instance.cpuMillis());
        dto.put("diskMb", instance.diskMb());
        dto.put("ports", instance.ports());
        dto.put("config", instance.config());
        dto.put("state", instance.state());
        dto.put("lastExitCode", instance.lastExitCode());
        dto.put("mcServerId", instance.mcServerId());
        dto.put("tenantId", instance.tenantId());
        dto.put("remark", instance.remark());
        dto.put("domainSlug", instance.domainSlug());
        dto.put("domainEnabled", instance.domainEnabled());
        dto.put("p2pEnabled", instance.p2pEnabled());
        dto.put("p2pWhitelist", instance.p2pWhitelist());
        dto.put("nodeTrust", instance.nodeTrust());
        dto.put("modpack", instance.modpack());
        dto.put("coreFallbackHistory", instance.coreFallbackHistory());
        dto.put("startDetect", instance.startDetect());
        dto.put("autoRestart", instance.autoRestart());
        dto.put("autoStart", instance.autoStart());
        dto.put("createdAt", instance.createdAt());
        dto.put("updatedAt", instance.updatedAt());
        return dto;
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword.toLowerCase());
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
