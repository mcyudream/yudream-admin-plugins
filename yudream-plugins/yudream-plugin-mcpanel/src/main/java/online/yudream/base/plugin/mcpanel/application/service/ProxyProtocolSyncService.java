package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PROXY protocol 与「节点接入方式」的自动同步（单端口入口配套）。
 *
 * <p>为什么必须跟着模式走：实例侧开启 {@code proxies.proxy-protocol} 后，服务端会**要求每个连接
 * 都带 PROXY 头**——经入口（mc-router）转发没问题，但玩家直连会被拒绝。所以
 * <ul>
 *   <li>节点切到「单端口入口」→ 该节点上支持的服务端类型自动开启；</li>
 *   <li>切到其它接入方式（直连/自填/frp/打洞）→ 自动关闭，恢复可直连；</li>
 *   <li>只翻配置里已存在的键，文件不存在/键缺失/类型不支持一律记为 skipped 并给出原因（不报错中断）。</li>
 * </ul>
 * 全部尽力而为：节点离线、文件缺失只记录在返回结果与审计里，绝不阻断模式切换本身。
 */
public class ProxyProtocolSyncService {

    /** 节点上的实例清单（bootstrap 装配；避免对实例应用服务的硬耦合）。 */
    public interface InstancesOnNode {
        List<McpanelInstance> onNode(String nodeId);
    }

    private final InstancesOnNode instances;
    private final ServerConfigService configs;
    private final McpanelInstanceAppService.AuditRecorder audit;

    public ProxyProtocolSyncService(InstancesOnNode instances, ServerConfigService configs,
                                    McpanelInstanceAppService.AuditRecorder audit) {
        this.instances = instances;
        this.configs = configs;
        this.audit = audit;
    }

    /**
     * 节点接入方式变化时同步该节点上的全部实例。
     *
     * @return {@code {mode, checked, changed, skipped, errors}}（skipped/errors 为可读说明列表）
     */
    public Map<String, Object> syncForNode(String actor, String nodeId, String accessMode) {
        String mode = NodeAccess.normalizeMode(accessMode);
        boolean enable = NodeAccess.MODE_ENTRY.equals(mode);
        List<McpanelInstance> targets = instances.onNode(nodeId);
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int changed = 0;
        for (McpanelInstance instance : targets) {
            try {
                Map<String, Object> result = configs.setProxyProtocol(instance.tenantId(), instance.id(),
                        instance.kind(), enable);
                if (Boolean.TRUE.equals(result.get("changed"))) {
                    changed++;
                }
                if (!Boolean.TRUE.equals(result.get("supported"))) {
                    skipped.add(instance.name() + "：" + result.get("reason"));
                }
            }
            catch (RuntimeException error) {
                // 键缺失/文件不存在/节点离线：如实记录，不影响其它实例与模式切换本身
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                if (message.contains("还没有") || message.contains("没有 ") || message.contains("不需要")) {
                    skipped.add(instance.name() + "：" + message);
                }
                else {
                    errors.add(instance.name() + "：" + message);
                }
            }
        }
        if (changed > 0 || !errors.isEmpty()) {
            record(actor, "node.access-mode.proxy-protocol", nodeId,
                    "接入方式切换为 " + NodeAccess.label(mode) + "：同步 " + targets.size()
                            + " 个实例，变更 " + changed + "，跳过 " + skipped.size() + "，失败 " + errors.size());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("modeLabel", NodeAccess.label(mode));
        result.put("enabled", enable);
        result.put("checked", targets.size());
        result.put("changed", changed);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return result;
    }

    /** 单实例同步（域名分配/释放、手动一键开关时的统一出口）。 */
    public Map<String, Object> syncForInstance(String actor, McpanelInstance instance, boolean enable) {
        Map<String, Object> result = configs.setProxyProtocol(instance.tenantId(), instance.id(),
                instance.kind(), enable);
        if (Boolean.TRUE.equals(result.get("changed"))) {
            record(actor, "instance.proxy-protocol", instance.id(),
                    (enable ? "开启" : "关闭") + " PROXY protocol（" + result.get("key") + "）");
        }
        return result;
    }

    private void record(String actor, String action, String targetId, String detail) {
        if (audit != null) {
            audit.record(actor, action, "node", targetId, detail, null);
        }
    }
}
