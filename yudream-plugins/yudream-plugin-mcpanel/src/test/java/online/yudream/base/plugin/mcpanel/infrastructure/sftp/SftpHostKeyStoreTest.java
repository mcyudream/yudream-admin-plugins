package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 多算法家族 host key 仓库：家族覆盖、跨实例指纹稳定、存量单密钥兼容与损坏重建。 */
class SftpHostKeyStoreTest {

    @Test
    void freshStoreCoversEd25519EcdsaAndRsaFamilies() {
        List<KeyPair> keys = new SftpHostKeyStore(new InMemoryPluginFileStore()).loadOrCreateAll();

        assertEquals(3, keys.size(), "应同时提供三个算法家族: " + typesOf(keys));
        assertTrue(typesOf(keys).contains("ssh-ed25519"), typesOf(keys).toString());
        assertTrue(typesOf(keys).contains("ssh-rsa"), typesOf(keys).toString());
        assertTrue(typesOf(keys).stream().anyMatch(type -> type.startsWith("ecdsa-sha2-nistp")),
                typesOf(keys).toString());
    }

    @Test
    void persistedKeysSurviveAcrossStoreInstances() {
        InMemoryPluginFileStore files = new InMemoryPluginFileStore();

        List<KeyPair> first = new SftpHostKeyStore(files).loadOrCreateAll();
        List<KeyPair> second = new SftpHostKeyStore(files).loadOrCreateAll();

        assertEquals(typesOf(first), typesOf(second));
        for (int i = 0; i < first.size(); i++) {
            assertTrue(KeyUtils.compareKeys(first.get(i).getPublic(), second.get(i).getPublic()),
                    "重启（新实例）后 host key 指纹必须稳定: " + KeyUtils.getKeyType(first.get(i)));
        }
    }

    @Test
    void legacyRsaKeyIsKeptAndMissingFamiliesFilled() throws Exception {
        InMemoryPluginFileStore files = new InMemoryPluginFileStore();
        KeyPair legacy = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        writePrivateKey(files, SftpHostKeyStore.STORE_KEY, legacy);

        List<KeyPair> keys = new SftpHostKeyStore(files).loadOrCreateAll();

        assertTrue(KeyUtils.compareKeys(legacy.getPublic(), keys.get(0).getPublic()),
                "存量单密钥应原样保留，客户端指纹不受影响");
        assertEquals(3, keys.size(), "存量家族之外的缺口应补齐: " + typesOf(keys));
        assertEquals(1, keys.stream()
                .filter(pair -> "ssh-rsa".equals(KeyUtils.getKeyType(pair)))
                .count(), "存量已是 RSA 时不得再挂第二把 RSA");
        assertTrue(typesOf(keys).contains("ssh-ed25519"), typesOf(keys).toString());
        assertTrue(typesOf(keys).stream().anyMatch(type -> type.startsWith("ecdsa-sha2-nistp")),
                typesOf(keys).toString());
    }

    @Test
    void legacyEd25519KeyIsKeptAndMissingFamiliesFilled() throws Exception {
        InMemoryPluginFileStore files = new InMemoryPluginFileStore();
        // Ed25519 必须走 MINA 注册表生成（I2P eddsa 键类），JDK 原生键无法被 MINA 编码。
        KeyPair legacy = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        writePrivateKey(files, SftpHostKeyStore.STORE_KEY, legacy);

        List<KeyPair> keys = new SftpHostKeyStore(files).loadOrCreateAll();

        assertTrue(KeyUtils.compareKeys(legacy.getPublic(), keys.get(0).getPublic()),
                "存量 ed25519 应原样保留，客户端指纹不受影响");
        assertEquals(3, keys.size(), typesOf(keys).toString());
        assertEquals(1, keys.stream()
                .filter(pair -> "ssh-ed25519".equals(KeyUtils.getKeyType(pair)))
                .count(), "存量已是 ed25519 时不得再挂第二把 ed25519");
    }

    @Test
    void corruptedLegacyFileIsRebuiltWithoutBlockingStartup() {
        InMemoryPluginFileStore files = new InMemoryPluginFileStore();
        byte[] garbage = "not-an-openssh-key".getBytes(StandardCharsets.UTF_8);
        files.put(SftpHostKeyStore.STORE_KEY, new ByteArrayInputStream(garbage), garbage.length,
                "application/octet-stream");

        List<KeyPair> keys = new SftpHostKeyStore(files).loadOrCreateAll();

        assertEquals(3, keys.size(), "损坏的存量文件按无存量处理，家族照常补齐: " + typesOf(keys));
    }

    private static Set<String> typesOf(List<KeyPair> keys) {
        return keys.stream().map(KeyUtils::getKeyType).collect(Collectors.toSet());
    }

    private static void writePrivateKey(InMemoryPluginFileStore files, String storeKey, KeyPair pair)
            throws IOException, GeneralSecurityException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "test", null, output);
        byte[] encoded = output.toByteArray();
        files.put(storeKey, new ByteArrayInputStream(encoded), encoded.length, "application/octet-stream");
    }
}
