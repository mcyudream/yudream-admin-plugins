package online.yudream.base.plugin.mcpanel.application.cmd;

import java.util.List;

/**
 * 节点 bootstrap 注册命令（来自节点机器，非管理员）。
 */
public record BootstrapCmd(
        String enrollToken,
        String hostname,
        String agentVersion,
        List<String> caps,
        String tlsCertSha256) {
}
