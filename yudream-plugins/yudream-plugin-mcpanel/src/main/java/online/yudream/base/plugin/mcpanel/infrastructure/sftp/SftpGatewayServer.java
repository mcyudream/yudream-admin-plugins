package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.session.ServerSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.PublicKey;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SFTP 单端口网关：面板 JVM 内嵌一个 SSH 服务端点，桌面 SFTP 客户端
 * （WinSCP 等）连「面板地址 + 网关端口」，凭据是面板签发的
 * {@code mc-<短别名>} + 随机密码；网关认证通过后，再以节点 ftp.open
 * 的随机凭据向节点侧 SFTP 发起真正的 SSH 连接，两侧 sftp subsystem
 * 字节流对泵。节点无需对最终用户暴露任何端口，桌面客户端也不再直连节点。
 *
 * <p>生命周期：start/stop 可反复调用（设置热更）；close 终态释放。
 * 节点侧 host key 采用进程内 TOFU（host:port 首见记忆、变更拒绝），
 * 面板重启即重置——节点 SFTP 凭据本就是 2h 级随机凭据，此强度足够。
 */
public final class SftpGatewayServer implements AutoCloseable {

    private static final long NODE_CONNECT_TIMEOUT_MS = 10_000L;
    private static final long NODE_CHANNEL_TIMEOUT_MS = 15_000L;
    private static final int AUTH_FAILURE_LIMIT = 10;
    private static final long AUTH_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);

    private final SftpGatewayRegistry registry;
    private final SftpHostKeyStore hostKeys;
    private final AuthRateLimiter limiter = new AuthRateLimiter(AUTH_FAILURE_LIMIT, AUTH_WINDOW_MS);
    /** TOFU 已知节点 host key：host:port → 首见公钥。 */
    private final ConcurrentHashMap<String, PublicKey> knownNodeHostKeys = new ConcurrentHashMap<>();
    private final AtomicLong pumpSequence = new AtomicLong();

    private volatile ExecutorService pumpExecutor;
    private volatile SshServer server;
    private volatile SshClient nodeClient;

    public SftpGatewayServer(SftpGatewayRegistry registry, SftpHostKeyStore hostKeys) {
        this.registry = registry;
        this.hostKeys = hostKeys;
    }

    /** 绑定网关端口；重复调用先停旧再启新（端口变更热生效）。 */
    public synchronized void start(int port) throws IOException {
        stop();
        ExecutorService pumps = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mcpanel-sftp-gw-pump-" + pumpSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(new TofuServerKeyVerifier());
        client.start();
        SshServer ssh = SshServer.setUpDefaultServer();
        ssh.setHost("0.0.0.0");
        ssh.setPort(port);
        // 多家族主机密钥（Ed25519/ECDSA/RSA）：部分客户端（FileZilla 等）不提供
        // ssh-ed25519，单密钥会因算法无交集在密钥交换阶段被拒。
        ssh.setKeyPairProvider(KeyPairProvider.wrap(hostKeys.loadOrCreateAll()));
        ssh.setPasswordAuthenticator(this::authenticate);
        ssh.setSubsystemFactories(List.of(new GatewaySftpSubsystemFactory(this::openNodeSftpChannel, pumps)));
        try {
            ssh.start();
        } catch (IOException error) {
            quietStop(client);
            pumps.shutdownNow();
            throw error;
        }
        this.nodeClient = client;
        this.pumpExecutor = pumps;
        this.server = ssh;
    }

    public synchronized void stop() {
        SshServer ssh = server;
        server = null;
        if (ssh != null) {
            try {
                ssh.stop(true);
            } catch (IOException ignored) {
                // 尽力而为：监听器随进程退出也会释放。
            }
        }
        SshClient client = nodeClient;
        nodeClient = null;
        quietStop(client);
        ExecutorService pumps = pumpExecutor;
        pumpExecutor = null;
        if (pumps != null) {
            pumps.shutdownNow();
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized int boundPort() {
        SshServer ssh = server;
        return ssh == null ? -1 : ssh.getPort();
    }

    @Override
    public void close() {
        stop();
        knownNodeHostKeys.clear();
    }

    /** 网关侧密码认证：限速门 + 注册表校验，成功则把条目绑定到会话。 */
    private boolean authenticate(String username, String password, ServerSession session) {
        String remote = remoteOf(session);
        long nowMs = System.currentTimeMillis();
        if (limiter.blocked(remote, nowMs)) {
            return false;
        }
        var entry = registry.authenticate(username, password);
        if (entry.isEmpty()) {
            limiter.recordFailure(remote, nowMs);
            return false;
        }
        limiter.reset(remote);
        session.setAttribute(GatewaySftpSubsystemFactory.ENTRY_KEY, entry.get());
        return true;
    }

    /** 以条目里的节点凭据拨号并打开 sftp subsystem（工厂回调，每条 SFTP 会话一次）。 */
    private ChannelSubsystem openNodeSftpChannel(SftpGatewayEntry entry) throws IOException {
        SshClient client = nodeClient;
        if (client == null) {
            throw new IOException("SFTP 网关已停止");
        }
        ClientSession session = client.connect(entry.nodeUser(), entry.dialHost(), entry.dialPort(), null, null)
                .verify(NODE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .getSession();
        try {
            session.addPasswordIdentity(entry.nodePassword());
            session.auth().verify(NODE_CHANNEL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            ChannelSubsystem subsystem = session.createSubsystemChannel(GatewaySftpSubsystemFactory.SUBSYSTEM_NAME);
            subsystem.open().verify(NODE_CHANNEL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            subsystem.onClose(() -> session.close(false));
            return subsystem;
        } catch (IOException | RuntimeException error) {
            session.close(false);
            // 拨号失败此前被完全吞掉，客户端只见裸断开；这里必须留痕（凭据过期/节点下线高频发生）。
            System.err.println("[mcpanel] SFTP 网关拨号节点失败: " + entry.dialHost() + ":" + entry.dialPort()
                    + " user=" + entry.nodeUser() + " 原因=" + error.getMessage());
            if (error instanceof IOException ioError) {
                throw ioError;
            }
            throw new IOException("SFTP 网关拨号节点失败", error);
        }
    }

    private static String remoteOf(ServerSession session) {
        SocketAddress address = session.getClientAddress();
        if (address instanceof InetSocketAddress inet) {
            return inet.getAddress() == null ? String.valueOf(inet) : inet.getAddress().getHostAddress();
        }
        return String.valueOf(address);
    }

    private static void quietStop(SshClient client) {
        if (client == null) {
            return;
        }
        client.stop();
    }

    /**
     * 节点 host key TOFU 校验：首见记忆，此后不一致即拒绝。目标地址与凭据
     * 都来自面板自身登记的 ftp.open 结果（非客户端输入），MITM 收益仅限
     * 单实例 2h 只读/写窗口，TOFU 强度与威胁模型匹配。
     */
    private final class TofuServerKeyVerifier implements ServerKeyVerifier {
        @Override
        public boolean verifyServerKey(ClientSession sshClientSession, SocketAddress address, PublicKey serverKey) {
            String key = address instanceof InetSocketAddress inet
                    ? inet.getHostString() + ":" + inet.getPort()
                    : String.valueOf(address);
            PublicKey known = knownNodeHostKeys.putIfAbsent(key, serverKey);
            return known == null || KeyUtils.compareKeys(known, serverKey);
        }
    }
}
