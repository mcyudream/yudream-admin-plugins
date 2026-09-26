package online.yudream.base.plugin.mcpanel.application.cmd;

/**
 * 创建节点命令。endpoint/tls 由管理员预配置；localDevelopment 为显式例外。
 * sftpHost 为 SFTP 对管理员的广告地址覆盖（选填，null=按 endpoint 推导）。
 * portRangeStart/portRangeEnd 为该节点实例端口分配区间（选填，null=用默认段）。
 * accessMode/accessHost 为该节点玩家接入方式与解析地址（见 NodeAccess；manual/frp 必填地址）。
 */
public record NodeCreateCmd(
        String name,
        String endpoint,
        String tlsMode,
        String pinSha256,
        boolean localDevelopment,
        String remark,
        Boolean enabled,
        Integer portRangeStart,
        Integer portRangeEnd,
        String sftpHost,
        String accessMode,
        String accessHost) {
}
