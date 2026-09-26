package online.yudream.base.plugin.mcpanel.domain.aggregate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板侧实例聚合（文档存储形态）。字段与节点 instance.* 载荷一一对应；
 * tenantId 自 M2 起参与数据范围（可空 = 平台直属）。
 */
public record McpanelInstance(
        String id, String nodeId, String name, String kind, String mcVersion,
        String templateKey, String image, List<String> command, Map<String, String> env,
        long memoryMb, long cpuMillis, long diskMb, List<PortMapping> ports,
        Map<String, String> config, String state, Integer lastExitCode,
        String mcServerId, String tenantId, String remark,
        String domainSlug, boolean domainEnabled, boolean p2pEnabled, List<String> p2pWhitelist,
        String nodeTrust, Map<String, Object> modpack, List<String> coreFallbackHistory,
        String startDetect, boolean autoRestart, boolean autoStart, long createdAt, long updatedAt) {

    public McpanelInstance {
        // 文档存储不接受 null 集合值：统一收敛为空容器/空串占位。
        command = command == null ? List.of() : List.copyOf(command);
        env = env == null ? Map.of() : Map.copyOf(env);
        ports = ports == null ? List.of() : List.copyOf(ports);
        p2pWhitelist = p2pWhitelist == null ? List.of() : List.copyOf(p2pWhitelist);
        modpack = modpack == null ? Map.of() : Map.copyOf(modpack);
        coreFallbackHistory = coreFallbackHistory == null ? List.of() : List.copyOf(coreFallbackHistory);
        templateKey = templateKey == null ? "" : templateKey;
        domainSlug = domainSlug == null ? "" : domainSlug;
        remark = remark == null ? "" : remark;
        nodeTrust = nodeTrust == null ? "platform" : nodeTrust;
        state = state == null || state.isBlank() ? "installing" : state;
        mcVersion = mcVersion == null ? "" : mcVersion;
        mcServerId = mcServerId == null ? "" : mcServerId;
        tenantId = tenantId == null || tenantId.isBlank() ? "" : tenantId;
        // 启动成功检测标记：空串 = 按服务端类型默认（节点侧 DefaultStartDetect）。
        startDetect = startDetect == null ? "" : startDetect;
        // 迁移旧「异常退出后自动拉起」占位（config.autoRestart，节点侧从未实现、不生效）：
        // 开启过 → 顶层 autoRestart 即刻生效；构造即剥离该键，任意整档写自然落库。
        // 放在构造器保证读侧稳定（不依赖是否恰好发生写入），且关得掉——事件任务
        // 保存写入的 config 已无该键，不会再被 OR 回来。
        if (config != null && config.containsKey("autoRestart")) {
            Object legacy = config.get("autoRestart");
            if (legacy != null && Boolean.parseBoolean(String.valueOf(legacy))) {
                autoRestart = true;
            }
            Map<String, String> stripped = new LinkedHashMap<>(config);
            stripped.remove("autoRestart");
            config = stripped;
        }
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public record PortMapping(int hostPort, int containerPort, String proto) {
    }

    public static McpanelInstance create(String id, String nodeId, String name, String kind,
                                         String mcVersion, String templateKey, String image,
                                         List<String> command, Map<String, String> env,
                                         long memoryMb, long cpuMillis, long diskMb,
                                         List<PortMapping> ports, Map<String, String> config,
                                         String tenantId, String remark, long now) {
        return new McpanelInstance(id, nodeId, name, kind, mcVersion, templateKey, image,
                List.copyOf(command), Map.copyOf(env), memoryMb, cpuMillis, diskMb,
                List.copyOf(ports), Map.copyOf(config), "installing", null, null, tenantId,
                remark, null, false, false, List.of(), "platform", Map.of(), List.of(), "",
                false, false, now, now);
    }

    public McpanelInstance withState(String newState, Integer exitCode, long now) {
        return new McpanelInstance(id, nodeId, name, kind, mcVersion, templateKey, image, command, env,
                memoryMb, cpuMillis, diskMb, ports, config, newState, exitCode, mcServerId, tenantId,
                remark, domainSlug, domainEnabled, p2pEnabled, p2pWhitelist, nodeTrust, modpack,
                coreFallbackHistory, startDetect, autoRestart, autoStart, createdAt, now);
    }

    public McpanelInstance withSpec(McpanelInstance updated, long now) {
        return new McpanelInstance(id, nodeId, updated.name, updated.kind, updated.mcVersion,
                updated.templateKey, updated.image, updated.command, updated.env,
                updated.memoryMb, updated.cpuMillis, updated.diskMb, updated.ports, updated.config,
                state, lastExitCode, updated.mcServerId, tenantId, updated.remark,
                updated.domainSlug, updated.domainEnabled, updated.p2pEnabled, updated.p2pWhitelist,
                updated.nodeTrust, modpack, coreFallbackHistory, updated.startDetect,
                updated.autoRestart(), updated.autoStart(), createdAt, now);
    }

    public McpanelInstance withBinding(String mcServerId, long now) {
        return new McpanelInstance(id, nodeId, name, kind, mcVersion, templateKey, image, command, env,
                memoryMb, cpuMillis, diskMb, ports, config, state, lastExitCode, mcServerId, tenantId,
                remark, domainSlug, domainEnabled, p2pEnabled, p2pWhitelist, nodeTrust, modpack,
                coreFallbackHistory, startDetect, autoRestart, autoStart, createdAt, now);
    }

    /** 启动命令变更（如 authlib 注入开关追加/移除 -javaagent），其余规格不变。 */
    public McpanelInstance withCommand(List<String> newCommand, long now) {
        return new McpanelInstance(id, nodeId, name, kind, mcVersion, templateKey, image,
                List.copyOf(newCommand), env, memoryMb, cpuMillis, diskMb, ports, config,
                state, lastExitCode, mcServerId, tenantId, remark, domainSlug, domainEnabled,
                p2pEnabled, p2pWhitelist, nodeTrust, modpack, coreFallbackHistory, startDetect,
                autoRestart, autoStart, createdAt, now);
    }

    /**
     * 域名自动分配状态变更（分配/释放）：slug 一旦落库即固定，实例改名不影响已发域名。
     */
    public McpanelInstance withDomain(String newSlug, boolean newEnabled, long now) {
        return new McpanelInstance(id, nodeId, name, kind, mcVersion, templateKey, image, command, env,
                memoryMb, cpuMillis, diskMb, ports, config, state, lastExitCode, mcServerId, tenantId,
                remark, newSlug, newEnabled, p2pEnabled, p2pWhitelist, nodeTrust, modpack,
                coreFallbackHistory, startDetect, autoRestart, autoStart, createdAt, now);
    }

    /** 启动成功检测标记变更（0.17.2+；空串 = 按类型默认）。 */
    public McpanelInstance withStartDetect(String newStartDetect, long now) {
        return new McpanelInstance(id, nodeId, name, kind, mcVersion, templateKey, image, command, env,
                memoryMb, cpuMillis, diskMb, ports, config, state, lastExitCode, mcServerId, tenantId,
                remark, domainSlug, domainEnabled, p2pEnabled, p2pWhitelist, nodeTrust, modpack,
                coreFallbackHistory, newStartDetect == null ? "" : newStartDetect,
                autoRestart, autoStart, createdAt, now);
    }

    /**
     * 事件触发型任务开关（对标 MCSM eventTask）：autoRestart = 未经面板操作的意外退出后
     * 自动拉起；autoStart = 节点上线（运行）后自动发起一次启动。面板侧语义，不下发节点。
     */
    public McpanelInstance withEventTask(boolean newAutoRestart, boolean newAutoStart, long now) {
        return new McpanelInstance(id, nodeId, name, kind, mcVersion, templateKey, image, command, env,
                memoryMb, cpuMillis, diskMb, ports, config, state, lastExitCode, mcServerId, tenantId,
                remark, domainSlug, domainEnabled, p2pEnabled, p2pWhitelist, nodeTrust, modpack,
                coreFallbackHistory, startDetect, newAutoRestart, newAutoStart, createdAt, now);
    }

    /** 声明规格求和（配额口径）：仅 cpu/mem 计入，disk 登记不下发硬限。 */
    public Map<String, Object> toNodePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", id);
        payload.put("name", name);
        payload.put("image", image);
        payload.put("command", command);
        payload.put("env", env);
        payload.put("memoryMb", memoryMb);
        payload.put("cpuMillis", cpuMillis);
        payload.put("diskMb", diskMb);
        List<Map<String, Object>> wirePorts = new ArrayList<>();
        for (PortMapping mapping : ports) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("port", mapping.hostPort());
            item.put("containerPort", mapping.containerPort());
            item.put("proto", mapping.proto());
            wirePorts.add(item);
        }
        payload.put("ports", wirePorts);
        payload.put("kind", kind);
        payload.put("config", config);
        // 启动成功检测（节点 0.6.2+）：空串 = 节点按类型默认；旧节点忽略未知字段。
        payload.put("startDetect", startDetect);
        return payload;
    }
}
