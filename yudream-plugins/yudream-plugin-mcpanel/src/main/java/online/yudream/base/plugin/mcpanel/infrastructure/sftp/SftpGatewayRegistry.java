package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * SFTP 网关别名注册表：短别名 → 会话条目的内存映射。
 *
 * <p>别名 6 位无歧义小写字母数字（去掉 0/o/1/l/i），唯一性只需在「当前活跃
 * 会话」内成立——条目与节点 ftp.open 同 TTL（≤2h），到期/关闭/重启即销毁，
 * 因此不落库、不做历史防碰撞。用户名支持两种写法：{@code mc-xxxxxx} 短别名，
 * 或完整 instanceId（调试/自动化兜底）。
 *
 * <p>别名只是路由键不是凭据：authenticate 必须同时校验随机密码（常数时间
 * 比较），别名可猜不构成攻击面。
 */
public class SftpGatewayRegistry {

    public static final String USERNAME_PREFIX = "mc-";
    /** 无歧义小写字母表（30 字符）：无 0/o/1/l/i，肉眼与手输都不歧义。 */
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int ALIAS_LENGTH = 6;
    private static final int PASSWORD_LENGTH = 24;
    private static final int ALIAS_MINT_ATTEMPTS = 8;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, SftpGatewayEntry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public SftpGatewayRegistry() {
        this(System::currentTimeMillis);
    }

    SftpGatewayRegistry(LongSupplier clock) {
        this.clock = clock;
    }

    /** 一次签发结果：password 明文只在此刻返回一次，注册表只存摘要。 */
    public record Minted(String alias, String username, String password, SftpGatewayEntry entry) {
    }

    /**
     * 为一次 ftp.open 签发别名与网关密码，并登记节点侧拨号目标与节点凭据。
     *
     * @param dialHost  节点 endpoint 的 host 部分（面板拨号已验证可达）
     * @param dialPort  节点 ftp.open 返回的 SFTP 端口
     */
    public Minted mint(String instanceId, String instanceName, String nodeId, String nodeName,
                       String dialHost, int dialPort, String nodeUser, String nodePassword,
                       long expiresAtMs) {
        purgeExpired(clock.getAsLong());
        String alias = uniqueAlias();
        String password = randomToken(PASSWORD_LENGTH);
        SftpGatewayEntry entry = new SftpGatewayEntry(alias, sha256Hex(password), instanceId, instanceName,
                nodeId, nodeName, dialHost, dialPort, nodeUser, nodePassword, expiresAtMs);
        entries.put(alias, entry);
        return new Minted(alias, USERNAME_PREFIX + alias, password, entry);
    }

    /**
     * 认证：username 支持 mc- 前缀短别名或完整 instanceId；密码摘要常数时间比较。
     * 过期条目一律拒绝并顺带清理。
     */
    public Optional<SftpGatewayEntry> authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        long nowMs = clock.getAsLong();
        purgeExpired(nowMs);
        String key = username.trim();
        if (key.startsWith(USERNAME_PREFIX)) {
            key = key.substring(USERNAME_PREFIX.length());
        }
        SftpGatewayEntry entry = entries.get(key);
        if (entry == null) {
            entry = findByInstanceId(key);
        }
        if (entry == null || entry.expiredAt(nowMs)) {
            return Optional.empty();
        }
        byte[] provided = sha256(password);
        if (!MessageDigest.isEqual(provided, HexFormat.of().parseHex(entry.passwordSha256()))) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /** 关闭某实例的全部网关会话（手动 ftp.close 时联动）。 */
    public int dropByInstance(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return 0;
        }
        int removed = 0;
        for (var iterator = entries.values().iterator(); iterator.hasNext(); ) {
            SftpGatewayEntry entry = iterator.next();
            if (instanceId.equals(entry.instanceId())) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public int purgeExpired(long nowMs) {
        int removed = 0;
        for (var iterator = entries.values().iterator(); iterator.hasNext(); ) {
            if (iterator.next().expiredAt(nowMs)) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    public int size() {
        return entries.size();
    }

    public int closeAll() {
        int removed = entries.size();
        entries.clear();
        return removed;
    }

    private SftpGatewayEntry findByInstanceId(String instanceId) {
        for (SftpGatewayEntry entry : entries.values()) {
            if (entry.instanceId().equals(instanceId)) {
                return entry;
            }
        }
        return null;
    }

    private String uniqueAlias() {
        for (int attempt = 0; attempt < ALIAS_MINT_ATTEMPTS; attempt++) {
            String candidate = randomToken(ALIAS_LENGTH);
            if (!entries.containsKey(candidate)) {
                return candidate;
            }
        }
        // 30^6 ≈ 7 亿空间 + 活跃会话极小，走到这里意味着随机源异常，直接失败暴露问题。
        throw new IllegalStateException("SFTP 网关别名生成冲突次数超限");
    }

    private String randomToken(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
