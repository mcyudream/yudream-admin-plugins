package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NodeStatsSnapshot → 面板稳定 DTO Map。字段名 = 协议 §5.2 冻结名 + reportedAt，
 * 前端按此渲染；load 允许 null（节点平台无 load 概念时）。
 */
public final class StatsViews {

    private StatsViews() {
    }

    public static Map<String, Object> toMap(NodeStatsSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cpuPercent", snapshot.cpuPercent());
        map.put("memUsedMb", snapshot.memUsedMb());
        map.put("memTotalMb", snapshot.memTotalMb());
        map.put("diskUsedGb", snapshot.diskUsedGb());
        map.put("diskTotalGb", snapshot.diskTotalGb());
        map.put("load", snapshot.load());
        List<Map<String, Object>> containers = new ArrayList<>();
        for (NodeStatsSnapshot.ContainerStat container : snapshot.containers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instanceId", container.instanceId());
            item.put("state", container.state());
            item.put("cpuPercent", container.cpuPercent());
            item.put("memUsedMb", container.memUsedMb());
            containers.add(item);
        }
        map.put("containers", containers);
        map.put("dockerVersion", snapshot.dockerVersion());
        map.put("agentVersion", snapshot.agentVersion());
        map.put("reportedAt", snapshot.reportedAtMs());
        return map;
    }
}
