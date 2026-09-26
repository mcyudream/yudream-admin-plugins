package online.yudream.base.plugin.mcpanel.domain.valobj;

import java.util.List;

/**
 * 节点最近一次资源快照（evt node.stats 的归一化视图，即协议 §5.2 冻结 schema）。
 * load1 在无此概念的平台上为 null。
 */
public record NodeStatsSnapshot(
        double cpuPercent,
        long memUsedMb,
        long memTotalMb,
        long diskUsedGb,
        long diskTotalGb,
        Double load,
        List<ContainerStat> containers,
        String dockerVersion,
        String agentVersion,
        long reportedAtMs) {

    public NodeStatsSnapshot {
        containers = containers == null ? List.of() : List.copyOf(containers);
    }

    /** 协议 §5.2：仅 instanceId/state/cpuPercent/memUsedMb 四键。 */
    public record ContainerStat(
            String instanceId,
            String state,
            double cpuPercent,
            long memUsedMb) {
    }
}
