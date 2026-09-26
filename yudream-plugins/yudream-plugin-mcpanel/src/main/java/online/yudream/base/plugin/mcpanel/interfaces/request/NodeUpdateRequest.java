package online.yudream.base.plugin.mcpanel.interfaces.request;

/**
 * 更新节点请求：null 字段保持不变。sftpHost 空串清除覆盖，null 保持不变。
 * portRangeStart/portRangeEnd 传 0/负数清除（回落默认段），null 保持不变。
 */
public record NodeUpdateRequest(
        String name,
        String endpoint,
        String tlsMode,
        String pinSha256,
        Boolean localDevelopment,
        String remark,
        Boolean enabled,
        Integer portRangeStart,
        Integer portRangeEnd,
        String sftpHost,
        String accessMode,
        String accessHost) {
}
