package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 网关 SSH host key 持久化：维持 Ed25519 / ECDSA P-256 / RSA-2048 三个算法
 * 家族，均以 OpenSSH 私钥格式存入平台文件存储，重启后指纹稳定。
 *
 * <p>桌面客户端与节点对主机密钥算法的支持差异很大——部分 FileZilla 构建不提供
 * ssh-ed25519，老客户端只认 ssh-rsa，节点侧（Go 服务端）只提供 ssh-ed25519
 * （该侧依赖网关 SSH 客户端能协商 ed25519）——只挂一把密钥会让无交集的一侧
 * 在密钥交换阶段被直接断开（Unable to negotiate key exchange for server host
 * key algorithms），因此网关两侧都需要覆盖多家族。
 *
 * <p>MINA sshd 2.14 的 Ed25519 支持只认配套的 I2P eddsa 库（JDK 原生 Ed25519
 * 无法注册/编码），该库已随插件 shade，Ed25519 家族据此用 MINA 注册表生成，
 * 生成失败时仅跳过该家族、不影响其余家族启动。
 *
 * <p>历史版本只持久化过一把密钥（{@link #STORE_KEY}，算法不限）：环境能解码时
 * 按家族原样保留，存量客户端指纹不变；读不回（含文件损坏）时忽略，缺失的家族
 * 按需生成并持久化，新部署不再写 {@link #STORE_KEY}。
 */
public final class SftpHostKeyStore {

    /** 历史单密钥文件：仅读取兼容，新部署不再写入。 */
    public static final String STORE_KEY = "mcpanel/sftp-gateway/host-key";
    public static final String ED25519_STORE_KEY = "mcpanel/sftp-gateway/host-key-ed25519";
    public static final String ECDSA_STORE_KEY = "mcpanel/sftp-gateway/host-key-ecdsa";
    public static final String RSA_STORE_KEY = "mcpanel/sftp-gateway/host-key-rsa";

    private static final int RSA_KEY_BITS = 2048;
    private static final String FAMILY_ED25519 = "ssh-ed25519";
    private static final String FAMILY_RSA = "ssh-rsa";
    /** ECDSA 各曲线（nistp256/384/521）合并视为一个家族。 */
    private static final String FAMILY_ECDSA = "ecdsa-sha2-nistp";

    private final PluginFileStore files;
    private volatile List<KeyPair> cached;

    public SftpHostKeyStore(PluginFileStore files) {
        this.files = files;
    }

    /** 加载或生成覆盖全部算法家族的 host key 集合；生成即持久化。 */
    public List<KeyPair> loadOrCreateAll() {
        List<KeyPair> existing = cached;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            cached = loadOrCreateUncached();
            return cached;
        }
    }

    private List<KeyPair> loadOrCreateUncached() {
        Map<String, KeyPair> byFamily = new LinkedHashMap<>();
        loadStoredAt(STORE_KEY).ifPresent(pair -> byFamily.putIfAbsent(familyOf(pair), pair));
        ensureFamily(byFamily, FAMILY_ED25519, ED25519_STORE_KEY, SftpHostKeyStore::generateEd25519);
        ensureFamily(byFamily, FAMILY_ECDSA, ECDSA_STORE_KEY, SftpHostKeyStore::generateEcdsaP256);
        ensureFamily(byFamily, FAMILY_RSA, RSA_STORE_KEY, SftpHostKeyStore::generateRsa2048);
        return List.copyOf(byFamily.values());
    }

    /** 家族缺位时读回该家族的持久化文件，仍缺则生成并落库；生成失败的家族跳过。 */
    private void ensureFamily(Map<String, KeyPair> byFamily, String family, String storeKey,
            HostKeyGenerator generator) {
        if (byFamily.containsKey(family)) {
            return;
        }
        KeyPair pair = loadStoredAt(storeKey).orElseGet(() -> generateAndStore(storeKey, generator));
        if (pair != null) {
            byFamily.put(family, pair);
        }
    }

    /** 家族键：类型名原样（ssh-rsa / ssh-ed25519 / …），ECDSA 各曲线合并。 */
    private static String familyOf(KeyPair pair) {
        String type = KeyUtils.getKeyType(pair);
        if (type != null && type.startsWith(FAMILY_ECDSA)) {
            return FAMILY_ECDSA;
        }
        return type == null ? "unknown" : type;
    }

    private Optional<KeyPair> loadStoredAt(String storeKey) {
        PluginStoredFile stored;
        try {
            stored = files.get(storeKey);
        } catch (RuntimeException missing) {
            // 宿主对象存储对缺失 key 抛 BizException（"文件不存在"）而非返回 null；
            // 首次启用必然未存储过 host key，按"无存量"处理走生成。
            return Optional.empty();
        }
        if (stored == null) {
            return Optional.empty();
        }
        try (InputStream input = stored.inputStream()) {
            NamedResource resource = storeKey::toString;
            Iterator<KeyPair> pairs = SecurityUtils
                    .loadKeyPairIdentities(null, resource, input, FilePasswordProvider.EMPTY)
                    .iterator();
            return pairs.hasNext() ? Optional.of(pairs.next()) : Optional.empty();
        } catch (IOException | GeneralSecurityException | RuntimeException error) {
            // 环境不支持该算法或文件损坏：按无存量处理，缺失家族重建。
            // host key 变更会让客户端告警，但好过网关永久起不来。
            return Optional.empty();
        }
    }

    /** 生成失败返回 null（调用方跳过该家族），避免单一算法问题拖垮网关启动。 */
    private KeyPair generateAndStore(String storeKey, HostKeyGenerator generator) {
        try {
            KeyPair keyPair = generator.generate();
            if (keyPair == null) {
                return null;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(2048);
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "mcpanel-sftp-gateway", null, output);
            byte[] encoded = output.toByteArray();
            files.put(storeKey, new ByteArrayInputStream(encoded), encoded.length, "application/octet-stream");
            return keyPair;
        } catch (GeneralSecurityException | IOException error) {
            throw new IllegalStateException("SFTP 网关 host key 生成/持久化失败: " + storeKey, error);
        }
    }

    /** MINA 的 Ed25519 生成/编码依赖 I2P eddsa 库（已随插件 shade），缺库时返回 null。 */
    private static KeyPair generateEd25519() {
        try {
            return KeyUtils.generateKeyPair(FAMILY_ED25519, 256);
        } catch (GeneralSecurityException | RuntimeException error) {
            return null;
        }
    }

    /** JDK 原生生成（MINA KeyUtils.generateKeyPair 按 SSH 算法名注册表解析，RSA/ECDSA 不稳）。 */
    private static KeyPair generateEcdsaP256() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static KeyPair generateRsa2048() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_KEY_BITS);
        return generator.generateKeyPair();
    }

    @FunctionalInterface
    private interface HostKeyGenerator {
        KeyPair generate() throws GeneralSecurityException;
    }
}
