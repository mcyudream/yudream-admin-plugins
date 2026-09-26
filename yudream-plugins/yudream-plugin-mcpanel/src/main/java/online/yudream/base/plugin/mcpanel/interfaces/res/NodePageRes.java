package online.yudream.base.plugin.mcpanel.interfaces.res;

import java.util.List;

/**
 * 分页响应：records + total（过滤后真实总数）。
 */
public record NodePageRes(List<NodeRes> records, long total, int page, int size) {
}
