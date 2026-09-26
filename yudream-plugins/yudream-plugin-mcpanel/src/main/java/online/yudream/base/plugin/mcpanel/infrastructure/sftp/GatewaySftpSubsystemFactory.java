package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.subsystem.SubsystemFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * 网关 sftp subsystem 工厂：从 SSH 会话属性取回认证阶段绑定的网关条目，
 * 据此打开节点侧通道并装配代理命令。未认证会话（无条目）直接拒绝。
 */
final class GatewaySftpSubsystemFactory implements SubsystemFactory {

    static final String SUBSYSTEM_NAME = "sftp";

    /** 认证成功后由 SftpGatewayServer 写入 ServerSession，subsystem 请求时读回。 */
    static final AttributeRepository.AttributeKey<SftpGatewayEntry> ENTRY_KEY =
            new AttributeRepository.AttributeKey<>();

    /** 按网关条目打开一条已连接、已认证、sftp subsystem 就绪的节点通道。 */
    interface NodeSftpChannelOpener {
        ChannelSubsystem open(SftpGatewayEntry entry) throws IOException;
    }

    private final NodeSftpChannelOpener opener;
    private final ExecutorService pumpExecutor;

    GatewaySftpSubsystemFactory(NodeSftpChannelOpener opener, ExecutorService pumpExecutor) {
        this.opener = opener;
        this.pumpExecutor = pumpExecutor;
    }

    @Override
    public String getName() {
        return SUBSYSTEM_NAME;
    }

    @Override
    public ProxySftpCommand createSubsystem(ChannelSession channel) throws IOException {
        SftpGatewayEntry entry = channel.getSession().getAttribute(ENTRY_KEY);
        if (entry == null) {
            throw new IOException("SFTP 网关：会话未携带网关认证条目");
        }
        return new ProxySftpCommand(() -> opener.open(entry), pumpExecutor);
    }
}
