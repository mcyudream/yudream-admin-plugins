package online.yudream.base.plugin.union.application.service;

import online.yudream.base.plugin.union.api.UnionFederationService;
import online.yudream.base.plugin.union.api.UnionJoinEvidenceProvider;
import online.yudream.base.plugin.union.domain.UnionBusinessException;
import online.yudream.base.plugin.union.domain.aggregate.UnionAuditLog;
import online.yudream.base.plugin.union.domain.aggregate.UnionJoinEvent;
import online.yudream.base.plugin.union.domain.aggregate.UnionPeer;
import online.yudream.base.plugin.union.infrastructure.PeerHttpClient;
import online.yudream.base.plugin.union.infrastructure.UnionCryptoService;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 联合 join 断言（协议 §3.2）：
 * B 侧 answerJoinQuery —— 从本地 join 证据签发短时签名断言；
 * A 侧 verifyRemoteJoin —— 向受信站并发查询、验签、唯一命中裁决与封禁检查。
 */
public class FederationJoinService implements UnionFederationService {

    private static final long JOIN_LOOKBACK_MILLIS = 60_000L;
    private static final long ASSERTION_TTL_MILLIS = 60_000L;
    private static final long CLOCK_SKEW_MILLIS = 5_000L;
    private static final int MAX_PEERS = 8;
    /** MC 服务端角色名上限，联合改写名同受约束。 */
    private static final int MANGLED_NAME_MAX = 16;

    private final PluginContext context;
    private final UnionRepository repository;
    private final UnionCryptoService cryptoService;
    private final PeerHttpClient peerHttpClient;
    private final UnionBanService banService;
    private final UnionDiscoveryService discovery;
    private final ExecutorService executor;

    /** jti 短窗去重（60s 内同 jti 响应丢弃）。 */
    private final ConcurrentHashMap<String, Long> seenJti = new ConcurrentHashMap<>();

    public FederationJoinService(PluginContext context, UnionRepository repository,
                                 UnionCryptoService cryptoService, PeerHttpClient peerHttpClient,
                                 UnionBanService banService, UnionDiscoveryService discovery,
                                 ExecutorService executor) {
        this.context = context;
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.peerHttpClient = peerHttpClient;
        this.banService = banService;
        this.discovery = discovery;
        this.executor = executor;
    }

    // ---- B 侧：应答断言查询 ----

    /** 命中返回响应体；未命中/封禁返回空（facade 映射 204）。 */
    public Optional<Map<String, Object>> answerJoinQuery(String username, String serverId, String aud, long now) {
        if (isBlank(username) || isBlank(serverId) || isBlank(aud)) {
            throw UnionBusinessException.invalid("username、serverId、aud 均为必填");
        }
        for (UnionJoinEvidenceProvider provider : context.extensions(UnionJoinEvidenceProvider.class)) {
            Optional<UnionJoinEvidenceProvider.JoinEvidence> evidence =
                    provider.findRecentJoin(username, serverId, now - JOIN_LOOKBACK_MILLIS);
            if (evidence.isEmpty()) {
                continue;
            }
            // 断言签发前自查联合封禁：命中一律静默 204（协议 §3.2）
            if (banService.isBanned(evidence.get().profileName(), now)) {
                return Optional.empty();
            }
            Map<String, Object> assertion = new LinkedHashMap<>();
            assertion.put("profileId", evidence.get().profileId());
            assertion.put("profileName", evidence.get().profileName());
            assertion.put("serverId", serverId);
            assertion.put("aud", aud);
            assertion.put("issuerKeyId", cryptoService.issuerKeyId());
            assertion.put("jti", randomHex(16));
            assertion.put("issuedAt", now);
            assertion.put("expiresAt", now + ASSERTION_TTL_MILLIS);
            assertion.put("textures", evidence.get().textureValue());
            String canonical = canonicalAssertion(assertion);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("assertion", assertion);
            body.put("signature", cryptoService.sign(canonical.getBytes(StandardCharsets.UTF_8)));
            // 溯源账本：本站为 aud 站签发了断言
            repository.saveJoinEvent(new UnionJoinEvent(
                    String.valueOf(assertion.get("jti")), UnionJoinEvent.DIRECTION_OUTBOUND,
                    aud, evidence.get().profileId(), evidence.get().profileName(),
                    serverId, cryptoService.issuerKeyId(), now));
            return Optional.of(body);
        }
        return Optional.empty();
    }

    // ---- A 侧：UnionFederationService ----

    @Override
    public Optional<FederatedProfile> verifyRemoteJoin(String username, String serverId) {
        long now = System.currentTimeMillis();
        List<UnionPeer> peers = repository.allPeers().stream()
                .filter(UnionPeer::trusted)
                .limit(MAX_PEERS)
                .toList();
        if (peers.isEmpty() || isBlank(username) || isBlank(serverId)) {
            return Optional.empty();
        }
        String normalizedQuery = UnionBanService.normalizeName(username);
        List<CompletableFuture<Optional<VerifiedAssertion>>> futures = peers.stream()
                .map(peer -> CompletableFuture.supplyAsync(() -> queryPeer(peer, username, serverId, normalizedQuery, now), executor))
                .toList();
        List<VerifiedAssertion> hits = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .toList();
        if (hits.size() > 1) {
            // 多站同时命中：防跨站撞名身份混淆，全部丢弃并告警（协议 §3.2）
            List<String> conflictSites = hits.stream().map(VerifiedAssertion::siteCode).distinct().toList();
            audit("SYSTEM", "", "join.conflict", normalizedQuery, "多站命中联合断言：" + conflictSites + "，已拒绝");
            return Optional.empty();
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        VerifiedAssertion hit = hits.get(0);
        // 封禁按 (归属站, 规范化名) 判定：只封对端声明的那个身份，不误伤其他站同名玩家
        if (banService.isBannedForSubject(hit.siteCode(), hit.profileName(), now)) {
            return Optional.empty();
        }
        UnionPeer peer = repository.findPeer(hit.siteCode()).orElse(null);
        String displayName = peer == null
                ? hit.profileName()
                : mangleDisplayName(peer, hit.profileName(), now);
        if (displayName == null) {
            // 改写名超 16 字符（MC 上限）无法安全展示：fail-closed 拒绝本次联合登录
            return Optional.empty();
        }
        return Optional.of(new FederatedProfile(hit.profileId(), displayName, hit.textureValue()));
    }

    /**
     * 名字空间改写（协议 §3.1 namePrefix）：`前缀_原名`，被占用时追加 _n；
     * 首次分配持久化，终身稳定。对端未声明前缀（未升级）保持 v1 原名直出。
     */
    private String mangleDisplayName(UnionPeer peer, String profileName, long now) {
        if (peer.namePrefix() == null || peer.namePrefix().isBlank()) {
            return profileName;
        }
        String normalized = UnionBanService.normalizeName(profileName);
        Optional<String> allocated = repository.findMangledName(peer.id(), normalized);
        if (allocated.isPresent()) {
            return allocated.get();
        }
        String candidate = peer.namePrefix() + "_" + normalized;
        int suffix = 0;
        while (repository.findNameMappingByMangled(candidate).isPresent()) {
            suffix++;
            candidate = peer.namePrefix() + "_" + normalized + "_" + suffix;
        }
        if (candidate.length() > MANGLED_NAME_MAX) {
            audit("SYSTEM", "", "join.mangleRejected", normalized,
                    "联合名空间改写超 " + MANGLED_NAME_MAX + " 字符（前缀 " + peer.namePrefix() + "），已拒绝");
            return null;
        }
        repository.saveNameMapping(peer.id(), normalized, profileName, candidate);
        return candidate;
    }

    @Override
    public List<String> trustedTextureHosts() {
        return repository.allPeers().stream()
                .filter(UnionPeer::trusted)
                .map(UnionPeer::textureBaseUrl)
                .filter(value -> value != null && !value.isBlank())
                .map(this::hostOf)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .distinct()
                .toList();
    }

    @Override
    public boolean isNameBanned(String name) {
        return banService.isBanned(name, System.currentTimeMillis());
    }

    @Override
    public String issuerKeyId() {
        return cryptoService.issuerKeyId();
    }

    @Override
    public Optional<SiteIdentity> siteIdentity() {
        Optional<String> code = discovery.siteCode();
        if (code.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SiteIdentity(code.get(),
                discovery.siteName().orElse(""),
                discovery.configuredOrigin().orElse("")));
    }

    private Optional<VerifiedAssertion> queryPeer(UnionPeer peer, String username, String serverId,
                                                  String normalizedQuery, long now) {
        try {
            String aud = discovery.siteCode().orElse("");
            if (aud.isEmpty()) {
                // 本站未配置 siteCode 时无法完成受众绑定：fail-closed 视为不可用
                return Optional.empty();
            }
            String url = UnionDiscoveryService.joinQueryUrl(peer)
                    + "?username=" + encode(username)
                    + "&serverId=" + encode(serverId)
                    + "&aud=" + encode(aud);
            PeerHttpClient.PeerResponse response = peerHttpClient.get(url, Optional.of(peer.tlsCertSha256()));
            if (response.body() == null || response.body().isBlank()) {
                return Optional.empty();
            }
            Map<String, Object> payload = JsonSupport.readMap(response.body());
            if (!(payload.get("assertion") instanceof Map<?, ?> rawAssertion)) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> assertion = (Map<String, Object>) rawAssertion;
            String canonical = canonicalAssertion(assertion);
            String signature = String.valueOf(payload.get("signature"));
            boolean signatureByPrimary = cryptoService.verify(peer.publicKeyPem(),
                    canonical.getBytes(StandardCharsets.UTF_8), signature);
            boolean signatureByBackup = !signatureByPrimary && cryptoService.verify(peer.backupPublicKeyPem(),
                    canonical.getBytes(StandardCharsets.UTF_8), signature);
            if (!signatureByPrimary && !signatureByBackup) {
                return Optional.empty();
            }
            String expectedIssuer = UnionCryptoService.issuerKeyId(
                    signatureByPrimary ? peer.publicKeyPem() : peer.backupPublicKeyPem());
            if (!expectedIssuer.equals(String.valueOf(assertion.get("issuerKeyId")))) {
                return Optional.empty();
            }
            String audClaim = String.valueOf(assertion.get("aud"));
            long issuedAt = number(assertion.get("issuedAt"));
            long expiresAt = number(assertion.get("expiresAt"));
            String profileId = String.valueOf(assertion.get("profileId"));
            String profileName = String.valueOf(assertion.get("profileName"));
            String textureValue = String.valueOf(assertion.get("textures"));
            String jti = String.valueOf(assertion.get("jti"));
            if (!aud.equals(audClaim)
                    || issuedAt <= 0 || issuedAt > now + CLOCK_SKEW_MILLIS
                    || expiresAt <= now || expiresAt - issuedAt > ASSERTION_TTL_MILLIS + CLOCK_SKEW_MILLIS
                    || !profileId.matches("[0-9a-fA-F]{32}")
                    || !profileName.matches("[A-Za-z0-9_]{3,16}")
                    || !serverId.equals(String.valueOf(assertion.get("serverId")))
                    || !UnionBanService.normalizeName(profileName).equals(normalizedQuery)
                    || !validRemoteTextures(textureValue, peer.textureBaseUrl(), profileId, profileName)
                    || jti.length() != 32 || !jti.matches("[0-9a-fA-F]{32}")
                    || alreadySeen(jti, now)) {
                return Optional.empty();
            }
            pruneJti(now);
            // 溯源账本：本站验证了来自对端站的跨站登录
            repository.saveJoinEvent(new UnionJoinEvent(
                    jti, UnionJoinEvent.DIRECTION_INBOUND,
                    peer.id(), profileId, profileName, serverId,
                    String.valueOf(assertion.get("issuerKeyId")), now));
            return Optional.of(new VerifiedAssertion(peer.id(), profileId, profileName, textureValue));
        } catch (RuntimeException ignored) {
            // 单站失败不阻塞整体（协议 §4.5）
            return Optional.empty();
        }
    }

    /** 跨站登录统计：入站（外站玩家进本站服）与出站（本站签发断言）分计。 */
    public Map<String, Object> stats(long now) {
        List<UnionJoinEvent> events = repository.allJoinEvents();
        long inbound = events.stream().filter(e -> UnionJoinEvent.DIRECTION_INBOUND.equals(e.direction())).count();
        long outbound = events.size() - inbound;
        long today = events.stream().filter(e -> e.recordedAt() >= startOfDay(now)).count();
        long last7 = events.stream().filter(e -> e.recordedAt() >= now - 7 * 24 * 3600_000L).count();
        long last30 = events.stream().filter(e -> e.recordedAt() >= now - 30 * 24 * 3600_000L).count();
        Map<String, Long> bySite = new LinkedHashMap<>();
        events.stream()
                .filter(e -> UnionJoinEvent.DIRECTION_INBOUND.equals(e.direction()))
                .forEach(e -> bySite.merge(e.siteCode(), 1L, Long::sum));
        long distinctPlayers = events.stream()
                .filter(e -> UnionJoinEvent.DIRECTION_INBOUND.equals(e.direction()))
                .map(UnionJoinEvent::profileId)
                .distinct()
                .count();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inboundTotal", inbound);
        body.put("outboundTotal", outbound);
        body.put("today", today);
        body.put("last7", last7);
        body.put("last30", last30);
        body.put("distinctPlayers", distinctPlayers);
        body.put("bySite", bySite.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(entry -> Map.of("siteCode", entry.getKey(), "count", entry.getValue()))
                .toList());
        return body;
    }

    /** 事件日志分页（默认时间倒序），site/name 可选过滤；name 按规范化前缀匹配，排序字段走白名单。 */
    public Map<String, Object> pageLog(String site, String name, int page, int size, String sortBy,
                                        boolean descending, long now) {
        List<UnionJoinEvent> events = new ArrayList<>(repository.allJoinEvents());
        if (!isBlank(site)) {
            events.removeIf(event -> !site.equals(event.siteCode()));
        }
        if (!isBlank(name)) {
            String normalized = UnionBanService.normalizeName(name);
            events.removeIf(event -> event.profileName() == null
                    || !UnionBanService.normalizeName(event.profileName()).startsWith(normalized));
        }
        java.util.Comparator<UnionJoinEvent> primary = switch (sortBy == null ? "recordedAt" : sortBy) {
            case "siteCode" -> java.util.Comparator.comparing(e -> nullableText(e.siteCode()));
            case "profileName" -> java.util.Comparator.comparing(e -> nullableText(e.profileName()));
            case "direction" -> java.util.Comparator.comparing(e -> nullableText(e.direction()));
            case "serverId" -> java.util.Comparator.comparing(e -> nullableText(e.serverId()));
            default -> java.util.Comparator.comparingLong(UnionJoinEvent::recordedAt);
        };
        java.util.Comparator<UnionJoinEvent> tiebreak = java.util.Comparator
                .comparingLong(UnionJoinEvent::recordedAt)
                .thenComparing(UnionJoinEvent::id);
        events.sort(descending ? primary.reversed().thenComparing(tiebreak.reversed())
                : primary.thenComparing(tiebreak));
        int from = Math.min((page - 1) * size, events.size());
        int to = Math.min(from + size, events.size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", events.subList(from, to).stream().map(this::eventView).toList());
        body.put("total", events.size());
        return body;
    }

    private static String nullableText(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> eventView(UnionJoinEvent event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", event.id());
        view.put("direction", event.direction());
        view.put("siteCode", event.siteCode());
        view.put("profileId", event.profileId());
        view.put("profileName", event.profileName());
        view.put("serverId", event.serverId());
        view.put("issuerKeyId", event.issuerKeyId());
        view.put("recordedAt", event.recordedAt());
        return view;
    }

    private static long startOfDay(long now) {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /**
     * 断言签名 canonical JSON：固定字段顺序、只接受协议字段，避免第三方实现依赖
     * JSON parser 的 Map 遍历顺序，也防止未签名扩展字段被悄然解释。
     */
    /**
     * 断言规范化（签名与验签共用同一字节序列）。时间戳字段统一归一为 Long：
     * 签名方内存里是 long（JSON 无引号），但断言经宿主 HTTP 下发时宿主的全局
     * Long→字符串序列化会把它变成带引号字符串——验证方若按收到的字符串形态
     * 重建规范串，字节必然与签名时不一致，导致跨站断言全部验签失败。
     */
    public static String canonicalAssertion(Map<String, Object> assertion) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String field : List.of("profileId", "profileName", "serverId", "aud", "issuerKeyId", "jti",
                "issuedAt", "expiresAt", "textures")) {
            Object value = assertion.get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                throw UnionBusinessException.invalid("联合断言缺少字段：" + field);
            }
            if ("issuedAt".equals(field) || "expiresAt".equals(field)) {
                value = Long.valueOf(String.valueOf(value).trim());
            }
            ordered.put(field, value);
        }
        return JsonSupport.write(ordered);
    }

    /**
     * textures value 必须是可解析的 base64 JSON，profile 声明与断言一致，SKIN/CAPE URL
     * 只能指向该 peer 的 HTTPS 纹理 host。
     */
    private boolean validRemoteTextures(String value, String textureBaseUrl, String profileId, String profileName) {
        try {
            String expectedHost = URI.create(textureBaseUrl).getHost();
            if (expectedHost == null || expectedHost.isBlank()) {
                return false;
            }
            String decoded = new String(java.util.Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            Map<String, Object> payload = JsonSupport.readMap(decoded);
            if (!profileId.equalsIgnoreCase(String.valueOf(payload.get("profileId")))
                    || !profileName.equals(String.valueOf(payload.get("profileName")))) {
                return false;
            }
            Object texturesNode = payload.get("textures");
            if (!(texturesNode instanceof Map<?, ?> textures)) {
                return false;
            }
            for (String kind : List.of("SKIN", "CAPE")) {
                Object raw = textures.get(kind);
                if (raw == null) {
                    continue;
                }
                if (!(raw instanceof Map<?, ?> node)) {
                    return false;
                }
                Object rawUrl = node.get("url");
                URI uri = rawUrl == null ? null : URI.create(String.valueOf(rawUrl));
                if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                        || !expectedHost.equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private boolean alreadySeen(String jti, long now) {
        while (true) {
            Long previous = seenJti.putIfAbsent(jti, now);
            if (previous == null) {
                return false;
            }
            if (now - previous <= ASSERTION_TTL_MILLIS) {
                return true;
            }
            if (seenJti.replace(jti, previous, now)) {
                return false;
            }
        }
    }

    private void pruneJti(long now) {
        if (seenJti.size() > 4096) {
            seenJti.entrySet().removeIf(entry -> now - entry.getValue() > ASSERTION_TTL_MILLIS);
        }
    }

    private Optional<String> hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? Optional.empty() : Optional.of(host);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void audit(String actorType, String actor, String action, String target, String detail) {
        repository.saveAudit(new UnionAuditLog(UUID.randomUUID().toString().replace("-", ""), actorType, actor,
                action, target, detail, System.currentTimeMillis()));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        new java.security.SecureRandom().nextBytes(random);
        StringBuilder builder = new StringBuilder();
        for (byte item : random) {
            builder.append(String.format("%02x", item));
        }
        return builder.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record VerifiedAssertion(String siteCode, String profileId, String profileName, String textureValue) {
    }
}
