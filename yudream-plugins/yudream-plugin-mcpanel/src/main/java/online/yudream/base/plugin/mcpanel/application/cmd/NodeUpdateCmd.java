package online.yudream.base.plugin.mcpanel.application.cmd;

/**
 * 更新节点命令：null 字段保持不变。修改 endpoint/tls 会触发控制信道重拨。
 * sftpHost 传空串清除覆盖，null 保持不变。
 * portRangeStart/portRangeEnd 传 0/负数清除（回落默认段），null 保持不变。
 * accessMode 为 direct/manual/frp/p2p（未知值回落 direct）；accessHost 传空串清除，null 保持不变。
 */
public record NodeUpdateCmd(
        String nodeId,
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
