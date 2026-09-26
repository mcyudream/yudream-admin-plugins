package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

/**
 * SFTP 网关会话条目：一次「开通临时传输」对应一条。
 *
 * <p>条目只存在于活跃期（expiresAtMs 前有效，TTL 与节点侧 ftp.open 一致），
 * 纯内存、不落库，面板重启即全部失效；密码只存 SHA-256。
 *
 * @param alias         6 位短名（不含 mc- 前缀），活跃期内全局唯一
 * @param passwordSha256 网关侧随机凭据摘要（客户端输入与它常数时间比较）
 * @param dialHost/dialPort 节点侧 SFTP 拨号目标（来自节点 aggregate endpoint，非用户输入）
 * @param nodeUser/nodePassword 节点 ftp.open 签发的随机账号（网关代持，不下发客户端）
 */
public record SftpGatewayEntry(
        String alias,
        String passwordSha256,
        String instanceId,
        String instanceName,
        String nodeId,
        String nodeName,
        String dialHost,
        int dialPort,
        String nodeUser,
        String nodePassword,
        long expiresAtMs) {

    public boolean expiredAt(long nowMs) {
        return nowMs >= expiresAtMs;
    }
}
