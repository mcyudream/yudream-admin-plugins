package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SFTP 网关端到端回路：进程内起真实 SFTP 节点替身（MINA + SftpSubsystem，
 * 根目录钉在 @TempDir），网关挂注册表后由标准 SSH 客户端以 mc-短别名 +
 * 随机密码接入，走完 认证 → 拨号 → subsystem 双向泵 全链路（写=客户端→节点，
 * 读=节点→客户端）。
 */
class SftpGatewayProxyLoopTest {

    private static final String NODE_USER = "node-user";
    private static final String NODE_PASSWORD = "node-pass";

    @TempDir
    Path nodeRoot;

    private SshServer node;
    private SftpGatewayRegistry registry;
    private SftpGatewayServer gateway;
    private SshClient client;
    private SftpGatewayRegistry.Minted minted;

    @BeforeEach
    void setUp() throws Exception {
        node = SshServer.setUpDefaultServer();
        node.setHost("127.0.0.1");
        node.setPort(0);
        node.setPasswordAuthenticator((username, password, session) ->
                NODE_USER.equals(username) && NODE_PASSWORD.equals(password));
        node.setKeyPairProvider(KeyPairProvider.wrap(rsaKeyPair()));
        node.setFileSystemFactory(new VirtualFileSystemFactory(nodeRoot));
        node.setSubsystemFactories(java.util.List.of(new SftpSubsystemFactory()));
        node.start();

        registry = new SftpGatewayRegistry();
        gateway = new SftpGatewayServer(registry, new SftpHostKeyStore(new InMemoryPluginFileStore()));
        gateway.start(0);
        minted = registry.mint("inst-e2e", "e2e-实例", "node-e2e", "节点A",
                "127.0.0.1", node.getPort(), NODE_USER, NODE_PASSWORD,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));

        client = SshClient.setUpDefaultClient();
        client.start();
    }

    @AfterEach
    void tearDown() {
        quiet(() -> client.stop());
        quiet(() -> gateway.close());
        quiet(() -> node.stop(true));
    }

    @Test
    void clientAuthenticatesWithAliasAndTransfersThroughGateway() throws Exception {
        ClientSession session = client.connect(minted.username(), "127.0.0.1", gateway.boundPort(), null, null)
                .verify(10_000, TimeUnit.MILLISECONDS)
                .getSession();
        session.addPasswordIdentity(minted.password());
        session.auth().verify(10_000, TimeUnit.MILLISECONDS);

        byte[] payload = "gateway-e2e-回路字节".getBytes(StandardCharsets.UTF_8);
        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            try (OutputStream output = sftp.write("/gateway-e2e.txt")) {
                output.write(payload);
            }
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            try (InputStream input = sftp.read("/gateway-e2e.txt")) {
                input.transferTo(received);
            }
            assertArrayEquals(payload, received.toByteArray());
        }

        Path written = nodeRoot.resolve("gateway-e2e.txt");
        assertTrue(Files.exists(written), "文件应穿过网关落到节点替身根目录");
        assertArrayEquals(payload, Files.readAllBytes(written));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        ClientSession session = client.connect(minted.username(), "127.0.0.1", gateway.boundPort(), null, null)
                .verify(10_000, TimeUnit.MILLISECONDS)
                .getSession();
        session.addPasswordIdentity("not-the-password");
        assertThrows(Exception.class, () -> session.auth().verify(10_000, TimeUnit.MILLISECONDS));
    }

    @Test
    void dropByInstanceRevokesGatewayAccess() throws Exception {
        assertEquals(1, registry.dropByInstance("inst-e2e"));

        ClientSession session = client.connect(minted.username(), "127.0.0.1", gateway.boundPort(), null, null)
                .verify(10_000, TimeUnit.MILLISECONDS)
                .getSession();
        session.addPasswordIdentity(minted.password());
        assertThrows(Exception.class, () -> session.auth().verify(10_000, TimeUnit.MILLISECONDS));
        assertFalse(registry.authenticate(minted.username(), minted.password()).isPresent());
    }

    @Test
    void clientWithoutEd25519HostKeySupportStillConnects() throws Exception {
        // 复现部分 FileZilla 构建的主机密钥偏好：不提供 ssh-ed25519。修复前网关
        // 只有单一算法、与客户端无交集，会在密钥交换阶段被服务端 KEY_EXCHANGE_FAILED 断开。
        java.util.concurrent.atomic.AtomicReference<String> negotiatedType =
                new java.util.concurrent.atomic.AtomicReference<>();
        SshClient restricted = SshClient.setUpDefaultClient();
        restricted.setSignatureFactories(java.util.List.of(
                org.apache.sshd.common.signature.BuiltinSignatures.nistp256,
                org.apache.sshd.common.signature.BuiltinSignatures.rsaSHA512,
                org.apache.sshd.common.signature.BuiltinSignatures.rsaSHA256,
                org.apache.sshd.common.signature.BuiltinSignatures.rsa));
        restricted.setServerKeyVerifier((session, address, serverKey) -> {
            negotiatedType.set(org.apache.sshd.common.config.keys.KeyUtils.getKeyType(serverKey));
            return true;
        });
        restricted.start();
        try {
            ClientSession session = restricted
                    .connect(minted.username(), "127.0.0.1", gateway.boundPort(), null, null)
                    .verify(10_000, TimeUnit.MILLISECONDS)
                    .getSession();
            session.addPasswordIdentity(minted.password());
            session.auth().verify(10_000, TimeUnit.MILLISECONDS);
            assertTrue(session.isAuthenticated());
        } finally {
            quiet(restricted::stop);
        }
        String type = negotiatedType.get();
        assertTrue(type != null && !type.equals("ssh-ed25519"),
                "受限客户端应协商到 ECDSA/RSA 主机密钥，实际: " + type);
    }

    @Test
    void ed25519OnlyNodeIsReachableThroughGateway() throws Exception {
        // 复现真实节点条件：节点是 Go SSH 服务端，主机密钥只有 ssh-ed25519
        // （网关 JVM 必须能协商 ed25519，否则拨号因算法无交集被拒）。
        SshServer edNode = SshServer.setUpDefaultServer();
        edNode.setHost("127.0.0.1");
        edNode.setPort(0);
        edNode.setPasswordAuthenticator((username, password, session) ->
                NODE_USER.equals(username) && NODE_PASSWORD.equals(password));
        edNode.setKeyPairProvider(KeyPairProvider.wrap(
                org.apache.sshd.common.config.keys.KeyUtils.generateKeyPair("ssh-ed25519", 256)));
        edNode.setFileSystemFactory(new VirtualFileSystemFactory(nodeRoot));
        edNode.setSubsystemFactories(java.util.List.of(new SftpSubsystemFactory()));
        edNode.start();
        try {
            SftpGatewayRegistry.Minted edMinted = registry.mint("inst-ed25519", "ed25519-实例", "node-ed25519",
                    "节点B", "127.0.0.1", edNode.getPort(), NODE_USER, NODE_PASSWORD,
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));

            ClientSession session = client.connect(edMinted.username(), "127.0.0.1", gateway.boundPort(), null, null)
                    .verify(10_000, TimeUnit.MILLISECONDS)
                    .getSession();
            session.addPasswordIdentity(edMinted.password());
            session.auth().verify(10_000, TimeUnit.MILLISECONDS);
            byte[] payload = "gateway-e2e-ed25519".getBytes(StandardCharsets.UTF_8);
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
                 OutputStream output = sftp.write("/gateway-ed25519.txt")) {
                output.write(payload);
            }
            assertArrayEquals(payload, Files.readAllBytes(nodeRoot.resolve("gateway-ed25519.txt")));
        } finally {
            quiet(edNode::stop);
        }
    }

    private static java.security.KeyPair rsaKeyPair() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void quiet(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception ignored) {
            // 收尾尽力而为。
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
