package online.yudream.base.plugin.mcpanel.interfaces.assembler;

import online.yudream.base.plugin.mcpanel.application.dto.EnrollResultDTO;
import online.yudream.base.plugin.mcpanel.application.dto.EnrollTokenDTO;
import online.yudream.base.plugin.mcpanel.application.dto.NodeDTO;
import online.yudream.base.plugin.mcpanel.interfaces.res.EnrollTokenRes;
import online.yudream.base.plugin.mcpanel.interfaces.res.NodePageRes;
import online.yudream.base.plugin.mcpanel.interfaces.res.NodeRes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO → HTTP 响应模型装配。
 */
public class McpanelWebAssembler {

    public NodeRes toRes(NodeDTO dto) {
        return new NodeRes(dto.id(), dto.name(), dto.endpoint(), dto.sftpHost(), dto.tlsMode(), dto.pinSha256(),
                dto.localDevelopment(), dto.remark(), dto.enabled(), dto.status(), dto.tenantId(),
                dto.agentVersion(), dto.reportedHost(), dto.sessionId(), dto.caps(),
                dto.dockerVersion(), dto.reportedCertSha256(), dto.connected(), dto.hasSecret(),
                dto.lastSeenAt(), dto.createdAt(), dto.updatedAt(),
                dto.portRangeStart(), dto.portRangeEnd(), dto.stats());
    }

    public NodePageRes toPageRes(List<NodeRes> records, long total, int page, int size) {
        return new NodePageRes(records, total, page, size);
    }

    public EnrollTokenRes toRes(EnrollTokenDTO dto) {
        return new EnrollTokenRes(dto.token(), dto.expiresAt());
    }

    /** bootstrap 机器面响应体（rawJson 下发，不走 wrapped）。 */
    public Map<String, Object> toBootstrapRes(EnrollResultDTO dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", dto.nodeId());
        map.put("nodeSecret", dto.nodeSecret());
        map.put("serverTimeMs", dto.serverTimeMs());
        map.put("controlPlan", dto.controlPlan());
        return map;
    }
}
