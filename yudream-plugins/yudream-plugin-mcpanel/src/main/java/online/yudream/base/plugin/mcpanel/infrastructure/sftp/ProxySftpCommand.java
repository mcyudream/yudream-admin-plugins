package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandDirectErrorStreamAware;
import org.apache.sshd.server.command.CommandDirectInputStreamAware;
import org.apache.sshd.server.command.CommandDirectOutputStreamAware;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SFTP 代理 subsystem：网关视角的「sftp」命令实现。
 *
 * <p>客户端 SSH 已在认证阶段终结（别名/密码属于网关），节点侧 SSH 由网关
 * 以 ftp.open 凭据重建；本类只做 SFTP 子系统字节流的双向泵——SSH 通道本身
 * 带流控窗口，逐段 pipe 即可，网关不解析 SFTP 协议内容。
 *
 * <p>任一方向 EOF/异常都触发收尾：退出回调让框架关客户端通道，destroy
 * 关节点通道，两侧任一断开不会留下半开连接。
 */
final class ProxySftpCommand implements Command, CommandDirectInputStreamAware,
        CommandDirectOutputStreamAware, CommandDirectErrorStreamAware {

    private static final int BUFFER_SIZE = 32 * 1024;

    private final ProxySftpCommandOpener opener;
    private final ExecutorService pumpExecutor;
    private final AtomicBoolean tornDown = new AtomicBoolean();

    private InputStream clientIn;
    private OutputStream clientOut;
    @SuppressWarnings("unused")
    private OutputStream clientErr;
    private ExitCallback exitCallback;
    private volatile ChannelSubsystem nodeChannel;
    private volatile Future<?> clientToNodeFuture;

    /** 打开一条已连接、已认证、sftp subsystem 已就绪的节点通道。 */
    interface ProxySftpCommandOpener {
        ChannelSubsystem open() throws IOException;
    }

    ProxySftpCommand(ProxySftpCommandOpener opener, ExecutorService pumpExecutor) {
        this.opener = opener;
        this.pumpExecutor = pumpExecutor;
    }

    @Override
    public void setInputStream(InputStream in) {
        this.clientIn = in;
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.clientOut = out;
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.clientErr = err;
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    @Override
    public void start(ChannelSession channel, Environment environment) throws IOException {
        // 节点不可达/凭据失效在此刻抛出 → subsystem 启动失败 → 客户端立刻得到明确失败。
        ChannelSubsystem subsystem = opener.open();
        nodeChannel = subsystem;
        OutputStream nodeIn = subsystem.getInvertedIn();
        InputStream nodeOut = subsystem.getInvertedOut();
        pumpExecutor.submit(() -> pumpNodeToClient(nodeOut));
        clientToNodeFuture = pumpExecutor.submit(() -> pumpClientToNode(nodeIn));
    }

    private void pumpNodeToClient(InputStream nodeOut) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int read;
            while ((read = nodeOut.read(buffer)) >= 0) {
                if (read > 0) {
                    clientOut.write(buffer, 0, read);
                    clientOut.flush();
                }
            }
            finish(0);
        } catch (IOException ignored) {
            finish(1);
        }
    }

    private void pumpClientToNode(OutputStream nodeIn) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int read;
            while ((read = clientIn.read(buffer)) >= 0) {
                if (read > 0) {
                    nodeIn.write(buffer, 0, read);
                    nodeIn.flush();
                }
            }
        } catch (IOException ignored) {
            tearDown();
        }
    }

    private void finish(int exitCode) {
        tearDown();
        ExitCallback callback = exitCallback;
        if (callback != null) {
            callback.onExit(exitCode);
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        tearDown();
    }

    private void tearDown() {
        if (!tornDown.compareAndSet(false, true)) {
            return;
        }
        ChannelSubsystem subsystem = nodeChannel;
        if (subsystem != null) {
            subsystem.close(false);
        }
        Future<?> pending = clientToNodeFuture;
        if (pending != null) {
            pending.cancel(true);
        }
    }
}
