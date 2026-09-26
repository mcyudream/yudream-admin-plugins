package online.yudream.base.plugin.mcpanel.interfaces.request;

import java.util.List;

/**
 * 节点 bootstrap 请求（协议 §2）。凭据只走 body，禁止进 URL/查询串。
 */
public record BootstrapRequest(
        String enrollToken,
        String hostname,
        String agentVersion,
        List<String> caps,
        String tlsCertSha256) {
}
