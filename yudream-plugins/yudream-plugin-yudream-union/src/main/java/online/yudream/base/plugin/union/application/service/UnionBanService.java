package online.yudream.base.plugin.union.application.service;

import online.yudream.base.plugin.union.domain.UnionBusinessException;
import online.yudream.base.plugin.union.domain.aggregate.UnionAuditLog;
import online.yudream.base.plugin.union.domain.aggregate.UnionBan;
import online.yudream.base.plugin.union.domain.aggregate.UnionPeer;
import online.yudream.base.plugin.union.infrastructure.PeerHttpClient;
import online.yudream.base.plugin.union.infrastructure.UnionCryptoService;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.infrastructure.support.JsonSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 联合封禁账本。
 *
 * <p>所有状态变更都是 append-only 事件（每条都有本站单调 seq）；同一 (归属站, 规范化名)
 * 的每个 sourceSite 取最新事件决定其贡献。这样对端按 cursor 增量同步时，ACTIVE → LIFTED →
 * ACTIVE 永远不会因原记录被覆盖而漏掉。本站管理员对外来条目的本地解除以 override
 * 实现，且不会伪造/转发他站的解除事件。归属维度（subjectSite）：blank = v1 旧语义全域
 * 生效；带归属的事件只作用于该站点的同名身份，杜绝跨站同名连坐。</p>
 */
public class UnionBanService {

    private static final int MAX_NAME_LENGTH = 16;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MAX_EVIDENCE_LENGTH = 2_000;

    private final UnionRepository repository;
    private final PeerHttpClient peerHttpClient;
    private final UnionCryptoService cryptoService;

    public UnionBanService(UnionRepository repository, PeerHttpClient peerHttpClient, UnionCryptoService cryptoService) {
        this.repository = repository;
        this.peerHttpClient = peerHttpClient;
        this.cryptoService = cryptoService;
    }

    /** 协议规范化：trim + 小写 + 剥离 § 颜色码。 */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("§.", "").trim().toLowerCase(Locale.ROOT);
    }

    /** 被封者归属：site = 归属站 siteCode（改写名命中映射时为对端，否则本站）。 */
    private record BanSubject(String site, String name, String nameLower) {
    }

    /**
     * 封禁输入解析：输入联合改写名（如 bsit_123456）时解析回 (对端, 原名)，
     * 否则视为本站自己的角色名。账本键始终是规范化原名。
     */
    private BanSubject resolveSubject(String input, String localSiteCode) {
        String normalized = requireName(input);
        return repository.findNameMappingByMangled(normalized)
                .map(document -> new BanSubject(string(document, "siteCode"),
                        string(document, "name"), string(document, "nameLower")))
                .orElseGet(() -> new BanSubject(localSiteCode, input.trim(), normalized));
    }

    /** 本站管理员新增封禁（追加 ACTIVE 事件；清除旧本地解除覆盖）。 */
    public UnionBan addBan(String name, String reason, String evidence, String sourceSite, String createdBy, long now) {
        if (blank(sourceSite)) {
            throw UnionBusinessException.invalid("请先配置本站 siteCode");
        }
        BanSubject subject = resolveSubject(name, sourceSite);
        repository.deleteBanOverride(subject.nameLower());
        UnionBan ban = appendEvent(subject.name(), subject.nameLower(), requireLength(reason, "封禁原因", MAX_REASON_LENGTH),
                sourceSite, subject.site(), trimToLength(evidence, MAX_EVIDENCE_LENGTH), createdBy, now, null,
                UnionBan.STATUS_ACTIVE, null, null);
        audit("ADMIN", createdBy, "ban.add", subject.nameLower(), "联合封禁：" + ban.reason());
        return ban;
    }

    /**
     * 本站管理员解除。本站 source 会追加 LIFTED 事件供直连对端同步；外来 source 只写
     * 本地 override，防止把本站管理员的裁决冒充为远端站的裁决。
     */
    public void liftBan(String name, String liftedBy, String localSiteCode, long now) {
        if (!isBanned(name, now)) {
            throw UnionBusinessException.invalid("该角色名没有生效中的封禁");
        }
        BanSubject subject = resolveSubject(name, localSiteCode);
        repository.saveBanOverride(subject.nameLower(), subject.site(), liftedBy, now);
        if (hasActiveSource(subject.nameLower(), localSiteCode, subject.site(), now)) {
            appendEvent(subject.name(), subject.nameLower(), "解除本地联合封禁", localSiteCode, subject.site(), null,
                    liftedBy, now, null, UnionBan.STATUS_LIFTED, liftedBy, now);
        }
        audit("ADMIN", liftedBy, "ban.lift", subject.nameLower(), "解除联合封禁（含本地覆盖）");
    }

    /** 规范化角色名是否命中本站视角的 ACTIVE 封禁（subject = 本站）。 */
    public boolean isBanned(String name, long now) {
        return isBannedForSubject(localSiteCode(), name, now);
    }

    /**
     * 归属维度封禁判定：事件命中条件 = 规范化名一致 且 (事件无归属 或 归属一致)。
     * 无归属事件（v1 旧数据）保持全域生效；归属事件只影响对应站点，杜绝跨站同名连坐。
     */
    public boolean isBannedForSubject(String subjectSite, String name, long now) {
        String normalized = normalizeName(name);
        if (normalized.isEmpty() || repository.findBanOverride(normalized, subjectSite).isPresent()) {
            return false;
        }
        return latestEventsBySource(normalized, subjectSite).values().stream().anyMatch(ban -> ban.activeAt(now));
    }

    /** 拉取全部受信站账本增量，单站失败不影响其他站点。 */
    public int pullFromPeers(Iterable<UnionPeer> peers, long now) {
        int applied = 0;
        for (UnionPeer peer : peers) {
            if (!peer.trusted()) {
                continue;
            }
            try {
                String url = UnionDiscoveryService.bansUrl(peer) + "?since=" + peer.banCursor();
                PeerHttpClient.PeerResponse response = peerHttpClient.get(url, Optional.of(peer.tlsCertSha256()));
                applied += appliedFrom(peer, JsonSupport.readMap(response.body()), now);
            } catch (RuntimeException ignored) {
                // 协议要求降级：受信站不可达不影响本站与其他站点。
            }
        }
        return applied;
    }

    /** 对端主动推送（可选）：共享 peerSecret 鉴权，但同样只允许该 peer 署名。 */
    public void applyReport(String bearerSecret, Map<String, Object> banBody, long now) {
        String digest = UnionCryptoService.sha256Hex(bearerSecret == null ? "" : bearerSecret);
        UnionPeer peer = repository.allPeers().stream()
                .filter(item -> item.trusted() && digest.equals(item.peerSecretSha256()))
                .findFirst()
                .orElseThrow(() -> new UnionBusinessException("auth.badToken", 401, "推送密钥无效"));
        String normalized = requireName(string(banBody, "name"));
        String subjectSite = string(banBody, "subjectSite");
        if (repository.findBanOverride(normalized, subjectSite).isPresent()) {
            return;
        }
        String status = statusOf(banBody.get("status"));
        appendEvent(string(banBody, "name").trim(), normalized,
                trimToLength(string(banBody, "reason"), MAX_REASON_LENGTH), peer.id(), subjectSite, null,
                "peer:" + peer.id(), number(banBody.get("createdAt"), now),
                nullableNumber(banBody.get("expiresAt")), status,
                UnionBan.STATUS_LIFTED.equals(status) ? "peer:" + peer.id() : null,
                UnionBan.STATUS_LIFTED.equals(status) ? now : null);
        audit("PEER", peer.id(), "ban.report", normalized, "受信站主动推送账本事件");
    }

    /**
     * 合并拉取的 append-only 增量。sourceSeq 只用于推进该 peer 的远端 cursor；本地保存
     * 自己的全局 seq。非法/越权事件不推进 cursor，避免静默跳过协议错误。
     */
    public int appliedFrom(UnionPeer peer, Map<String, Object> payload, long now) {
        if (!(payload.get("bans") instanceof List<?> rawEvents)) {
            return 0;
        }
        int applied = 0;
        long maxRemoteSeq = peer.banCursor();
        for (Object item : rawEvents) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) raw;
            long remoteSeq = number(event.get("seq"), -1L);
            if (remoteSeq <= maxRemoteSeq) {
                continue;
            }
            String name = string(event, "name");
            String normalized;
            String status;
            String subjectSite = string(event, "subjectSite");
            try {
                normalized = requireName(name);
                status = statusOf(event.get("status"));
                if (!peer.id().equals(string(event, "sourceSite"))) {
                    throw UnionBusinessException.invalid("账本事件 sourceSite 与受信站不一致");
                }
            } catch (UnionBusinessException ignored) {
                // 不推进 cursor：管理员须修复对端协议输出，不能静默跳过封禁事件。
                break;
            }
            if (repository.findBanOverride(normalized, subjectSite).isEmpty()) {
                appendEvent(name.trim(), normalized, trimToLength(string(event, "reason"), MAX_REASON_LENGTH),
                        peer.id(), subjectSite, null, "peer:" + peer.id(), number(event.get("createdAt"), now),
                        nullableNumber(event.get("expiresAt")), status,
                        UnionBan.STATUS_LIFTED.equals(status) ? "peer:" + peer.id() : null,
                        UnionBan.STATUS_LIFTED.equals(status) ? now : null);
                applied++;
            }
            maxRemoteSeq = remoteSeq;
        }
        if (maxRemoteSeq > peer.banCursor()) {
            repository.savePeer(new UnionPeer(peer.id(), peer.siteName(), peer.origin(), peer.apiRoot(),
                    peer.textureBaseUrl(), peer.publicKeyPem(), peer.backupPublicKeyPem(), peer.tlsCertSha256(),
                    peer.peerSecretSha256(), peer.status(), peer.addedBy(), peer.addedAt(), peer.note(),
                    peer.namePrefix(), maxRemoteSeq, peer.authlibPublicKeyPem()));
        }
        return applied;
    }

    private UnionBan appendEvent(String name, String nameLower, String reason, String sourceSite, String subjectSite,
                                 String evidence, String createdBy, long createdAt, Long expiresAt, String status,
                                 String liftedBy, Long liftedAt) {
        long seq = repository.reserveNextBanSeq();
        UnionBan event = new UnionBan(seq, name, nameLower, reason, sourceSite, subjectSite, evidence, createdBy,
                createdAt, expiresAt, status, liftedBy, liftedAt);
        repository.saveBan(event);
        return event;
    }

    private boolean hasActiveSource(String normalizedName, String sourceSite, String subjectSite, long now) {
        if (blank(sourceSite)) {
            return false;
        }
        UnionBan latest = latestEventsBySource(normalizedName, subjectSite).get(sourceSite);
        return latest != null && latest.activeAt(now);
    }

    /** 同名事件的最新裁决按来源分组；先按归属过滤再取最新，不同 subject 互不覆盖。 */
    private Map<String, UnionBan> latestEventsBySource(String normalizedName, String subjectSite) {
        Map<String, UnionBan> latest = new LinkedHashMap<>();
        for (UnionBan event : repository.bansByName(normalizedName)) {
            boolean subjectMatches = blank(event.subjectSite()) || blank(subjectSite)
                    || event.subjectSite().equals(subjectSite);
            if (!subjectMatches) {
                continue;
            }
            UnionBan previous = latest.get(event.sourceSite());
            if (previous == null || event.seq() > previous.seq()) {
                latest.put(event.sourceSite(), event);
            }
        }
        return latest;
    }

    /** 本站 siteCode（repository 直读，避免与发现服务相互依赖）。 */
    private String localSiteCode() {
        return repository.setting("site").map(document -> String.valueOf(document.get("siteCode"))).orElse("");
    }

    private void audit(String actorType, String actor, String action, String target, String detail) {
        repository.saveAudit(new UnionAuditLog(UUID.randomUUID().toString().replace("-", ""), actorType, actor,
                action, target, detail, System.currentTimeMillis()));
    }

    private static String requireName(String value) {
        String normalized = normalizeName(value);
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH || !normalized.matches("[a-z0-9_]+")) {
            throw UnionBusinessException.invalid("角色名必须为 1-16 位英数或下划线");
        }
        return normalized;
    }

    private static String statusOf(Object value) {
        String status = value == null || String.valueOf(value).isBlank()
                ? UnionBan.STATUS_ACTIVE : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (!UnionBan.STATUS_ACTIVE.equals(status) && !UnionBan.STATUS_LIFTED.equals(status)) {
            throw UnionBusinessException.invalid("账本事件 status 非法");
        }
        return status;
    }

    private static String requireLength(String value, String label, int limit) {
        String result = trim(value);
        if (result.isEmpty()) {
            throw UnionBusinessException.invalid(label + "不能为空");
        }
        return trimToLength(result, limit);
    }

    private static String trimToLength(String value, int limit) {
        String result = trim(value);
        return result.length() > limit ? result.substring(0, limit) : result;
    }

    private static String string(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item == null ? "" : String.valueOf(item);
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Long nullableNumber(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) {
            return null;
        }
        long parsed = number(value, Long.MIN_VALUE);
        return parsed == Long.MIN_VALUE ? null : parsed;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
