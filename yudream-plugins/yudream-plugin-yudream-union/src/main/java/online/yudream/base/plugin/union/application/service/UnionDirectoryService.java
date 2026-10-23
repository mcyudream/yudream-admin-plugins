package online.yudream.base.plugin.union.application.service;

import online.yudream.base.plugin.union.domain.UnionBusinessException;
import online.yudream.base.plugin.union.domain.aggregate.UnionAuditLog;
import online.yudream.base.plugin.union.domain.aggregate.UnionEnrollToken;
import online.yudream.base.plugin.union.domain.aggregate.UnionPeer;
import online.yudream.base.plugin.union.infrastructure.PeerHttpClient;
import online.yudream.base.plugin.union.infrastructure.UnionCryptoService;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.infrastructure.support.JsonSupport;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 受信站目录与信任注册（协议 §3.5：一次性 enrollToken + CAS 消费 + TOFU 固定公钥/证书指纹）。 */
public class UnionDirectoryService {

    private static final long ENROLL_TOKEN_TTL = 5 * 60_000L;

    private final UnionRepository repository;
    private final UnionCryptoService cryptoService;
    private final PeerHttpClient peerHttpClient;
    private final UnionDiscoveryService discovery;

    public UnionDirectoryService(UnionRepository repository, UnionCryptoService cryptoService,
                                 PeerHttpClient peerHttpClient, UnionDiscoveryService discovery) {
        this.repository = repository;
        this.cryptoService = cryptoService;
        this.peerHttpClient = peerHttpClient;
        this.discovery = discovery;
    }

    /** 签发一次性注册令牌（明文只出现一次，落库 SHA-256）。 */
    public Map<String, Object> issueEnrollToken(String actor, long now) {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String digest = UnionCryptoService.sha256Hex(token);
        repository.saveEnrollToken(new UnionEnrollToken(digest, actor, now, now + ENROLL_TOKEN_TTL, false, null));
        audit(actor, "enroll.token", "", "签发一次性信任注册令牌");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enrollToken", token);
        body.put("expiresAt", now + ENROLL_TOKEN_TTL);
        return body;
    }

    /** 信任注册：CAS 消费令牌 → 拉取对端发现文档 → TOFU 固定 TLS 指纹与签名公钥。 */
    public Map<String, Object> enroll(String origin, String enrollToken, String note, String actor, long now) {
        if (isBlank(origin) || isBlank(enrollToken)) {
            throw UnionBusinessException.invalid("origin 与 enrollToken 为必填");
        }
        if (origin.length() > 255 || enrollToken.length() > 512) {
            throw UnionBusinessException.invalid("payload 长度超限");
        }
        String normalizedOrigin = canonicalOrigin(origin);
        // 查无此令牌最常见原因是令牌签发自另一站点（令牌只在本站仓库有效）或复制不完整；过期单独提示。
        UnionEnrollToken token = repository.findEnrollToken(UnionCryptoService.sha256Hex(enrollToken))
                .orElseThrow(() -> UnionBusinessException.badToken("注册令牌无效：令牌须由本站签发且未被使用，请在本站「签发注册令牌」后复制完整令牌"));
        if (token.expiresAt() <= now) {
            throw UnionBusinessException.badToken("注册令牌已过期（有效期 5 分钟），请重新签发");
        }
        if (token.consumed()) {
            throw UnionBusinessException.tokenConsumed();
        }
        String metaUrl = normalizedOrigin + UnionDiscoveryService.UNION_API_PREFIX + "/peer/meta";
        String apiRoot = normalizedOrigin + UnionDiscoveryService.UNION_API_PREFIX;
        PeerHttpClient.PeerResponse response;
        try {
            response = peerHttpClient.get(metaUrl, Optional.empty());
        } catch (RuntimeException e) {
            throw UnionBusinessException.peerUnreachable("拉取对端发现文档失败：" + e.getMessage());
        }
        Map<String, Object> meta = JsonSupport.readMap(response.body());
        if (!UnionDiscoveryService.UNION_VERSION.equals(String.valueOf(meta.get("unionVersion")))) {
            throw UnionBusinessException.peerUnreachable("对端联合协议主版本不受支持");
        }
        if (!(meta.get("site") instanceof Map<?, ?> rawSite)) {
            throw UnionBusinessException.peerUnreachable("对端发现文档缺少 site 块");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> site = (Map<String, Object>) rawSite;
        String siteCode = stringOrNull(site.get("code"));
        if (siteCode == null || siteCode.isBlank() || siteCode.length() > 64) {
            throw UnionBusinessException.peerUnreachable("对端未配置 siteCode");
        }
        if (siteCode.equals(discovery.siteCode().orElse(""))) {
            throw UnionBusinessException.invalid("不能把本站注册为受信站点");
        }
        String declaredOrigin = stringOrNull(site.get("origin"));
        if (declaredOrigin == null || !normalizedOrigin.equals(stripTrailingSlash(declaredOrigin))) {
            throw UnionBusinessException.peerUnreachable("对端发现文档 site.origin 与注册 origin 不一致");
        }
        if (repository.findPeer(siteCode).isPresent()) {
            throw new UnionBusinessException("peer-exists", 409, "该站点已注册");
        }
        String endpointApiRoot = endpointApiRoot(meta, normalizedOrigin);
        String textureBaseUrl = requiredPeerUrl(meta, "textureBaseUrl", normalizedOrigin);
        String primaryKey = requiredPem(meta.get("signaturePublickey"), "signaturePublickey");
        String backupKey = optionalPem(meta.get("backupSignaturePublickey"));
        requireFeature(meta, "join-assertions");
        // 全部远端事实验证完成后才 CAS 消费一次性令牌；网络/格式错误允许同 token 修正后重试。
        if (!repository.consumeEnrollToken(token, now)) {
            throw UnionBusinessException.tokenConsumed();
        }
        byte[] secretRandom = new byte[32];
        new SecureRandom().nextBytes(secretRandom);
        String peerSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretRandom);
        UnionPeer peer = new UnionPeer(
                siteCode,
                String.valueOf(site.get("name")),
                normalizedOrigin,
                endpointApiRoot,
                textureBaseUrl,
                primaryKey,
                backupKey,
                response.tlsCertSha256(),
                UnionCryptoService.sha256Hex(peerSecret),
                UnionPeer.STATUS_TRUSTED,
                actor,
                now,
                trim(note),
                0L);
        repository.savePeer(peer);
        audit(actor, "enroll", siteCode, "信任注册站点（TOFU 固定 TLS 指纹与公钥）");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", UnionPeer.STATUS_TRUSTED);
        body.put("peerSecret", peerSecret);
        body.put("peer", peerView(peer));
        return body;
    }

    /** 公钥轮换：新主钥生效时旧主钥转备钥（7 天宽限由验签方执行）。 */
    public UnionPeer refreshPeer(String siteCode, String actor, long now) {
        UnionPeer peer = repository.findPeer(siteCode).orElseThrow(() -> UnionBusinessException.invalid("站点不存在"));
        PeerHttpClient.PeerResponse response;
        try {
            response = peerHttpClient.get(metaUrl(peer), Optional.of(peer.tlsCertSha256()));
        } catch (RuntimeException e) {
            throw UnionBusinessException.peerUnreachable("拉取对端发现文档失败：" + e.getMessage());
        }
        Map<String, Object> meta = JsonSupport.readMap(response.body());
        validatePeerMeta(meta, peer.origin(), peer.id());
        String newPrimary = requiredPem(meta.get("signaturePublickey"), "signaturePublickey");
        String backup = newPrimary.equals(peer.publicKeyPem())
                ? optionalPem(meta.get("backupSignaturePublickey"))
                : peer.publicKeyPem();
        UnionPeer updated = new UnionPeer(peer.id(), String.valueOf(((Map<?, ?>) meta.get("site")).get("name")),
                peer.origin(), endpointApiRoot(meta, peer.origin()), requiredPeerUrl(meta, "textureBaseUrl", peer.origin()), newPrimary, backup,
                peer.tlsCertSha256(), peer.peerSecretSha256(), peer.status(), peer.addedBy(), peer.addedAt(),
                peer.note(), peer.banCursor());
        repository.savePeer(updated);
        audit(actor, "peer.refresh", siteCode, "刷新受信站公钥/元数据");
        return updated;
    }

    public UnionPeer setPeerStatus(String siteCode, String status, String actor) {
        UnionPeer peer = repository.findPeer(siteCode).orElseThrow(() -> UnionBusinessException.invalid("站点不存在"));
        if (!UnionPeer.STATUS_TRUSTED.equals(status) && !UnionPeer.STATUS_BLOCKED.equals(status)) {
            throw UnionBusinessException.invalid("非法状态");
        }
        UnionPeer updated = new UnionPeer(peer.id(), peer.siteName(), peer.origin(), peer.apiRoot(),
                peer.textureBaseUrl(), peer.publicKeyPem(), peer.backupPublicKeyPem(), peer.tlsCertSha256(),
                peer.peerSecretSha256(), status, peer.addedBy(), peer.addedAt(), peer.note(), peer.banCursor());
        repository.savePeer(updated);
        audit(actor, UnionPeer.STATUS_BLOCKED.equals(status) ? "peer.block" : "peer.unblock", siteCode,
                UnionPeer.STATUS_BLOCKED.equals(status) ? "停用受信站点" : "恢复受信站点");
        return updated;
    }

    public void deletePeer(String siteCode, String actor) {
        repository.findPeer(siteCode).orElseThrow(() -> UnionBusinessException.invalid("站点不存在"));
        repository.deletePeer(siteCode);
        audit(actor, "peer.delete", siteCode, "删除受信站点");
    }

    public List<Map<String, Object>> listPeers(int page, int size) {
        return repository.allPeers().stream().skip((long) (page - 1) * size).limit(size)
                .map(this::peerView)
                .toList();
    }

    public long countPeers() {
        return repository.allPeers().size();
    }

    public Map<String, Object> peerView(UnionPeer peer) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", peer.id());
        view.put("siteName", peer.siteName());
        view.put("origin", peer.origin());
        view.put("apiRoot", peer.apiRoot());
        view.put("textureBaseUrl", peer.textureBaseUrl());
        view.put("keyFingerprint", fingerprintOf(peer.publicKeyPem()));
        view.put("backupKeyFingerprint", peer.backupPublicKeyPem() == null ? null : fingerprintOf(peer.backupPublicKeyPem()));
        view.put("tlsCertSha256", peer.tlsCertSha256());
        view.put("status", peer.status());
        view.put("addedBy", peer.addedBy());
        view.put("addedAt", peer.addedAt());
        view.put("note", peer.note());
        view.put("banCursor", String.valueOf(peer.banCursor()));
        return view;
    }

    /** 受信站点聚合列表（封禁拉取/内部流程用，非视图）。 */
    public List<UnionPeer> trustedPeers() {
        return repository.allPeers().stream().filter(UnionPeer::trusted).toList();
    }

    /** 互通自检：按注册 pin 复检对端发现文档与公钥指纹一致性。 */
    public Map<String, Object> selfTest(String siteCode, long now) {
        UnionPeer peer = repository.findPeer(siteCode).orElseThrow(() -> UnionBusinessException.invalid("站点不存在"));
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            PeerHttpClient.PeerResponse response = peerHttpClient.get(metaUrl(peer), Optional.of(peer.tlsCertSha256()));
            Map<String, Object> meta = JsonSupport.readMap(response.body());
            boolean versionOk = UnionDiscoveryService.UNION_VERSION.equals(String.valueOf(meta.get("unionVersion")));
            String remoteKey = String.valueOf(meta.get("signaturePublickey"));
            boolean keyOk = remoteKey.equals(peer.publicKeyPem())
                    || remoteKey.equals(peer.backupPublicKeyPem());
            body.put("ok", versionOk && keyOk);
            body.put("detail", versionOk && keyOk ? "发现文档与公钥指纹一致" : "发现文档或公钥与注册时不一致，请执行刷新");
        } catch (RuntimeException e) {
            body.put("ok", false);
            body.put("detail", "对端不可达：" + e.getMessage());
        }
        audit("ADMIN", "peer.selftest", siteCode, String.valueOf(body.get("detail")));
        return body;
    }

    public Map<String, Object> siteSettings() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("siteCode", discovery.siteCode().orElse(""));
        body.put("siteName", discovery.siteName().orElse(""));
        body.put("origin", discovery.configuredOrigin().orElse(""));
        body.put("keyFingerprint", cryptoService.issuerKeyId());
        body.put("features", discovery.buildMeta().getOrDefault("features", List.of()));
        return body;
    }

    public void saveSite(String siteCode, String siteName, String actor) {
        if (siteCode == null || siteCode.isBlank() || siteCode.length() > 64) {
            throw UnionBusinessException.invalid("siteCode 为必填且 ≤64 字符");
        }
        String normalized = trim(siteCode);
        String existing = discovery.siteCode().orElse("");
        if (!existing.isBlank() && !existing.equals(normalized)) {
            throw UnionBusinessException.invalid("本站 siteCode 一旦启用即不可变更；变更会使既有受信站断言的 aud 校验失效");
        }
        discovery.saveSite(normalized, siteName);
        audit(actor, "settings.site", normalized, "配置本站联合身份");
    }

    @SuppressWarnings("unchecked")
    private void validatePeerMeta(Map<String, Object> meta, String expectedOrigin, String expectedSiteCode) {
        if (!UnionDiscoveryService.UNION_VERSION.equals(String.valueOf(meta.get("unionVersion")))) {
            throw UnionBusinessException.peerUnreachable("对端联合协议主版本不受支持");
        }
        if (!(meta.get("site") instanceof Map<?, ?> rawSite)) {
            throw UnionBusinessException.peerUnreachable("对端发现文档缺少 site 块");
        }
        Map<String, Object> site = (Map<String, Object>) rawSite;
        String siteCode = stringOrNull(site.get("code"));
        if (siteCode == null || siteCode.isBlank() || siteCode.length() > 64) {
            throw UnionBusinessException.peerUnreachable("对端未配置 siteCode");
        }
        if (expectedSiteCode != null && !expectedSiteCode.equals(siteCode)) {
            throw UnionBusinessException.peerUnreachable("对端 siteCode 与已注册站点不一致");
        }
        String declaredOrigin = stringOrNull(site.get("origin"));
        if (declaredOrigin == null || !expectedOrigin.equals(stripTrailingSlash(declaredOrigin))) {
            throw UnionBusinessException.peerUnreachable("对端发现文档 site.origin 与注册 origin 不一致");
        }
        requiredPeerUrl(meta, "textureBaseUrl", expectedOrigin);
        requiredPem(meta.get("signaturePublickey"), "signaturePublickey");
        requireFeature(meta, "join-assertions");
    }

    private String fingerprintOf(String publicKeyPem) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(UnionCryptoService.pemBody(publicKeyPem));
            byte[] digest = UnionCryptoService.sha256(der);
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 16; index++) {
                builder.append(String.format("%02x", digest[index]));
            }
            return builder.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void audit(String actor, String action, String target, String detail) {
        repository.saveAudit(new UnionAuditLog(UUID.randomUUID().toString().replace("-", ""), "ADMIN",
                actor == null ? "" : actor, action, target, detail, System.currentTimeMillis()));
    }

    private String requiredPeerUrl(Map<String, Object> meta, String key, String expectedOrigin) {
        String url = stringOrNull(meta.get(key));
        if (url == null) {
            throw UnionBusinessException.peerUnreachable("对端发现文档缺少 " + key);
        }
        String normalized = stripTrailingSlash(url);
        if (!normalized.startsWith(expectedOrigin + "/")) {
            throw UnionBusinessException.peerUnreachable("对端 " + key + " 不属于已验证 origin");
        }
        try {
            if (!"https".equalsIgnoreCase(java.net.URI.create(normalized).getScheme())) {
                throw UnionBusinessException.peerUnreachable("对端 " + key + " 必须为 https");
            }
        } catch (IllegalArgumentException e) {
            throw UnionBusinessException.peerUnreachable("对端 " + key + " 非法");
        }
        return normalized;
    }

    private String requiredPem(Object raw, String key) {
        String pem = stringOrNull(raw);
        if (pem == null) {
            throw UnionBusinessException.peerUnreachable("对端发现文档缺少 " + key);
        }
        try {
            UnionCryptoService.issuerKeyId(pem);
            return pem;
        } catch (RuntimeException e) {
            throw UnionBusinessException.peerUnreachable("对端 " + key + " 非法");
        }
    }

    private String optionalPem(Object raw) {
        String pem = stringOrNull(raw);
        if (pem == null) {
            return null;
        }
        try {
            UnionCryptoService.issuerKeyId(pem);
            return pem;
        } catch (RuntimeException e) {
            throw UnionBusinessException.peerUnreachable("对端 backupSignaturePublickey 非法");
        }
    }

    private void requireFeature(Map<String, Object> meta, String feature) {
        if (!(meta.get("features") instanceof List<?> features)
                || features.stream().map(String::valueOf).noneMatch(feature::equals)) {
            throw UnionBusinessException.peerUnreachable("对端未声明必需能力：" + feature);
        }
    }

    /**
     * 对端可在发现文档声明非默认 apiRoot；必须是同 origin 下的 HTTPS 路径，
     * 防止 meta 被劫持后把后续请求转向另一主机。
     */
    @SuppressWarnings("unchecked")
    private String endpointApiRoot(Map<String, Object> meta, String expectedOrigin) {
        String fallback = expectedOrigin + UnionDiscoveryService.UNION_API_PREFIX;
        if (!(meta.get("endpoints") instanceof Map<?, ?> rawEndpoints)) {
            return fallback;
        }
        String declared = stringOrNull(((Map<String, Object>) rawEndpoints).get("apiRoot"));
        if (declared == null) {
            return fallback;
        }
        String normalized = stripTrailingSlash(declared);
        if (!normalized.startsWith(expectedOrigin + "/")) {
            throw UnionBusinessException.peerUnreachable("对端 endpoints.apiRoot 不属于已验证 origin");
        }
        return normalized;
    }

    private static String metaUrl(UnionPeer peer) {
        String apiRoot = stripTrailingSlash(peer.apiRoot());
        // 兼容早期实现曾把 /peer/meta 错存为 apiRoot 的记录；刷新后会规范化保存。
        if (apiRoot.endsWith("/peer/meta")) {
            return apiRoot;
        }
        return apiRoot + "/peer/meta";
    }

    private static String normalizeApiRoot(UnionPeer peer) {
        String apiRoot = stripTrailingSlash(peer.apiRoot());
        return apiRoot.endsWith("/peer/meta")
                ? apiRoot.substring(0, apiRoot.length() - "/peer/meta".length())
                : apiRoot;
    }

    private static String stringOrNull(Object value) {
        return value == null || String.valueOf(value).equals("null") ? null : String.valueOf(value);
    }

    private static String canonicalOrigin(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw UnionBusinessException.invalid("origin 必须是无路径、无参数的 https 根地址");
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String origin = "https://" + host;
            if (uri.getPort() >= 0 && uri.getPort() != 443) {
                origin += ":" + uri.getPort();
            }
            return origin;
        } catch (UnionBusinessException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw UnionBusinessException.invalid("origin 必须是无路径、无参数的 https 根地址");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
