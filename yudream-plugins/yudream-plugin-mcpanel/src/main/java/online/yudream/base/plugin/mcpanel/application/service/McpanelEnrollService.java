package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.cmd.BootstrapCmd;
import online.yudream.base.plugin.mcpanel.application.dto.EnrollResultDTO;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.EnrollTokenRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.EnrollToken;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 节点注册用例（机器面，token 门禁，无登录态）。
 *
 * <p>安全裁决（v0.1 审查版 + 注册原子性专项修订）：
 * <ul>
 *   <li>统一鉴权失败码：令牌无效/过期一律 401 auth.badToken（不区分，避免枚举泄漏）；
 *       已消费 409 auth.tokenConsumed；pinned 指纹不符 401 auth.pinMismatch；
 *       超限 429 auth.rateLimited；节点停用 409 node-disabled（协议 §2 补充记录）。</li>
 *   <li>pinned 节点先比对上报 tlsCertSha256 == 管理员 pin，失败不消费令牌，
 *       管理员修正 pin 后同令牌可重试；管理员未预填 pin 时按 TOFU 以上报指纹
 *       登记初始 pin（自签简化，免登机取指纹；信任锚为一次性 enrollToken）。</li>
 *   <li>领取/注册整体包在 {@code nodeRepository.withNodeLock(nodeId)} 内：锁内
 *       重读 token 与节点最新状态，先 token CAS（claimOnce）再节点注册 CAS
 *       （markEnrolledOnce，仅合并注册字段、不回写管理员配置），与重签吊销
 *       （issueEnrollment 的 expireOnce）同锁串行；同宿主内"多 token 并发/重签后
 *       旧 token 领取"只可能产生一次注册、一个 secret。</li>
 *   <li>payload 长度上限（机器面防滥用，超限 400 invalid-request）：
 *       enrollToken ≤ 512、hostname ≤ 255、agentVersion ≤ 64、
 *       caps ≤ 32 项且每项 ≤ 64、tlsCertSha256 空或 64 位小写 hex。</li>
 * </ul>
 */
public class McpanelEnrollService {

    /** 全局滑动窗口限流：60 秒内最多 30 次 bootstrap 尝试。 */
    public static final int RATE_LIMIT = 30;
    static final long RATE_WINDOW_MS = 60_000L;

    static final int MAX_TOKEN_LENGTH = 512;
    static final int MAX_HOSTNAME_LENGTH = 255;
    static final int MAX_AGENT_VERSION_LENGTH = 64;
    static final int MAX_CAPS_ITEMS = 32;
    static final int MAX_CAP_ITEM_LENGTH = 64;

    private final McpanelNodeRepository nodeRepository;
    private final EnrollTokenRepository tokenRepository;
    private final NodeSecrets secrets;
    private final LongSupplier clock;

    private final AtomicLong windowStart = new AtomicLong();
    private final AtomicLong windowCount = new AtomicLong();

    public McpanelEnrollService(McpanelNodeRepository nodeRepository,
                                EnrollTokenRepository tokenRepository,
                                NodeSecrets secrets) {
        this(nodeRepository, tokenRepository, secrets, System::currentTimeMillis);
    }

    public McpanelEnrollService(McpanelNodeRepository nodeRepository,
                                EnrollTokenRepository tokenRepository,
                                NodeSecrets secrets,
                                LongSupplier clock) {
        this.nodeRepository = nodeRepository;
        this.tokenRepository = tokenRepository;
        this.secrets = secrets;
        this.clock = clock;
    }

    private void checkRateLimit() {
        long now = clock.getAsLong();
        long start = windowStart.get();
        if (now - start > RATE_WINDOW_MS) {
            if (windowStart.compareAndSet(start, now)) {
                windowCount.set(0);
            }
        }
        if (windowCount.incrementAndGet() > RATE_LIMIT) {
            throw new McpanelBusinessException("auth.rateLimited", 429, "注册尝试过于频繁，请稍后重试");
        }
    }

    /** bootstrap：限速 → payload 校验 → withNodeLock 内（重读→校验→claim CAS→注册 CAS→发 secret）。 */
    public EnrollResultDTO bootstrap(BootstrapCmd cmd) {
        checkRateLimit();
        validatePayload(cmd);
        long now = clock.getAsLong();
        String digest = NodeSecrets.sha256Hex(cmd.enrollToken().trim());
        EnrollToken token = tokenRepository.findByDigest(digest)
                .orElseThrow(() -> new McpanelBusinessException("auth.badToken", 401, "注册令牌无效"));
        if (token.expired(now)) {
            throw new McpanelBusinessException("auth.badToken", 401, "注册令牌无效");
        }
        McpanelNode node = nodeRepository.findById(token.nodeId())
                .orElseThrow(() -> new McpanelBusinessException("auth.badToken", 401, "注册令牌无效"));
        if (!node.enabled()) {
            throw new McpanelBusinessException("node-disabled", 409, "节点已停用，无法完成注册");
        }
        if (node.enrolled()) {
            throw new McpanelBusinessException("auth.tokenConsumed", 409, "节点已完成注册");
        }
        String hostname = blankToNull(cmd.hostname());
        String agentVersion = blankToNull(cmd.agentVersion());
        String reportedCert = blankToNull(cmd.tlsCertSha256());
        return nodeRepository.withNodeLock(node.id(), () -> {
            // 锁内重读最新 token 与节点：重签吊销/并发领取在同一条纹锁上串行。
            EnrollToken fresh = tokenRepository.findByDigest(digest)
                    .orElseThrow(() -> new McpanelBusinessException("auth.badToken", 401, "注册令牌无效"));
            if (fresh.expired(now)) {
                throw new McpanelBusinessException("auth.badToken", 401, "注册令牌无效");
            }
            McpanelNode current = nodeRepository.findById(node.id())
                    .orElseThrow(() -> new McpanelBusinessException("auth.badToken", 401, "注册令牌无效"));
            if (!current.enabled()) {
                throw new McpanelBusinessException("node-disabled", 409, "节点已停用，无法完成注册");
            }
            if (current.enrolled()) {
                throw new McpanelBusinessException("auth.tokenConsumed", 409, "节点已完成注册");
            }
            // pinned：管理员已预填指纹时严格比对（失败不消费令牌，修正后同令牌可重试）；
            // 未预填时 TOFU——以本次上报指纹登记为初始 pin（信任锚为一次性 enrollToken，
            // 持有 token 即等同可冒充节点，无新增攻击面），注册 CAS 与登记同一次写入。
            String adminPin = blankToNull(current.pinSha256());
            String reported = reportedCert == null ? "" : reportedCert.toLowerCase(Locale.ROOT);
            String pinToRegister = null;
            if ("pinned".equalsIgnoreCase(current.tlsMode())) {
                if (adminPin != null) {
                    if (!reported.equals(adminPin)) {
                        throw new McpanelBusinessException("auth.pinMismatch", 401,
                                "节点证书指纹与管理员预配置 pin 不一致");
                    }
                }
                else if (!reported.isEmpty()) {
                    pinToRegister = reported;
                }
            }
            // 先 token CAS（一次性兑换）；失败即另一并发领取已赢，本请求 409，不写注册。
            if (!tokenRepository.claimOnce(fresh, now)) {
                throw new McpanelBusinessException("auth.tokenConsumed", 409, "注册令牌已被使用");
            }
            // 注册 CAS：锁内基于最新节点仅合并注册字段（含 TOFU 初始 pin）；null = 已被
            // 并发注册赢，本请求 409。
            McpanelNode enrolled = nodeRepository.markEnrolledOnce(
                    current.id(), agentVersion, hostname, reportedCert, pinToRegister, now);
            if (enrolled == null) {
                throw new McpanelBusinessException("auth.tokenConsumed", 409, "节点已完成注册");
            }
            String secret = secrets.ensureSecret(node.id());
            return EnrollResultDTO.direct(node.id(), secret, now);
        });
    }

    /** 机器面 payload 上限（超限 400 invalid-request；token 缺失保持 401 语义）。 */
    private void validatePayload(BootstrapCmd cmd) {
        if (cmd == null) {
            throw new McpanelBusinessException("auth.badToken", 401, "注册令牌无效");
        }
        String token = cmd.enrollToken();
        if (token == null || token.isBlank()) {
            throw new McpanelBusinessException("auth.badToken", 401, "注册令牌无效");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw invalid("enrollToken 长度超限");
        }
        if (cmd.hostname() != null && cmd.hostname().length() > MAX_HOSTNAME_LENGTH) {
            throw invalid("hostname 长度超限");
        }
        if (cmd.agentVersion() != null && cmd.agentVersion().length() > MAX_AGENT_VERSION_LENGTH) {
            throw invalid("agentVersion 长度超限");
        }
        List<String> caps = cmd.caps();
        if (caps != null) {
            if (caps.size() > MAX_CAPS_ITEMS) {
                throw invalid("caps 数量超限");
            }
            for (String cap : caps) {
                if (cap != null && cap.length() > MAX_CAP_ITEM_LENGTH) {
                    throw invalid("caps 单项长度超限");
                }
            }
        }
        String cert = cmd.tlsCertSha256();
        if (cert != null && !cert.isBlank() && !cert.matches("[0-9a-f]{64}")) {
            throw invalid("tlsCertSha256 必须为 64 位小写 hex");
        }
    }

    private static McpanelBusinessException invalid(String message) {
        return McpanelBusinessException.invalid(message);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
