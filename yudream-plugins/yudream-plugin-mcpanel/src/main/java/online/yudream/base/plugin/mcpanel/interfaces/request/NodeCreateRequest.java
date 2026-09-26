package online.yudream.base.plugin.mcpanel.interfaces.request;

/**
 * 创建节点请求。endpoint 必须为 wss://authority（无路径）；
 * tlsMode=pkix|pinned；pinned 必须带 64 位 hex pinSha256；
 * localDevelopment 仅放宽 loopback 目标，绝不放宽 ws 明文。
 * sftpHost 为 SFTP 广告地址覆盖（选填，仅用于展示，面板不向其发起连接）。
 * portRangeStart/portRangeEnd 为实例端口分配区间（选填，null=用默认段）。
 */
public record NodeCreateRequest(
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
