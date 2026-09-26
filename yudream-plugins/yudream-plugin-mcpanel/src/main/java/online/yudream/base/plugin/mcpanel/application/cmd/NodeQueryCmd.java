package online.yudream.base.plugin.mcpanel.application.cmd;

/**
 * 节点分页查询命令。
 */
public record NodeQueryCmd(Integer page, Integer size, String status, String keyword) {
}
