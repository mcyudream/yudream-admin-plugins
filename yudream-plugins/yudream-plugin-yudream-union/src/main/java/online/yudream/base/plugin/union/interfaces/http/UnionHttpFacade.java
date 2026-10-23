package online.yudream.base.plugin.union.interfaces.http;

import online.yudream.base.plugin.union.application.service.FederationJoinService;
import online.yudream.base.plugin.union.application.service.UnionBanService;
import online.yudream.base.plugin.union.application.service.UnionDirectoryService;
import online.yudream.base.plugin.union.application.service.UnionDiscoveryService;
import online.yudream.base.plugin.union.domain.UnionBusinessException;
import online.yudream.base.plugin.union.domain.aggregate.UnionBan;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.infrastructure.support.JsonSupport;
import online.yudream.base.plugin.union.infrastructure.support.RateLimiter;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 联合认证 HTTP 门面：peer 机器面（协议错误体）+ admin 管理面（宿主信封）。 */
public class UnionHttpFacade {

    private static final String X_UNION_VERSION = "X-Union-Version";
    private static final int BANS_PULL_PAGE_LIMIT = 10;

    private final FederationJoinService federation;
    private final UnionBanService banService;
    private final UnionDirectoryService directory;
    private final UnionDiscoveryService discovery;
    private final UnionRepository repository;

    private final RateLimiter metaLimiter = new RateLimiter(60, 60_000L);
    private final RateLimiter joinLimiter = new RateLimiter(120, 60_000L);
    private final RateLimiter bansLimiter = new RateLimiter(60, 60_000L);
    private final RateLimiter enrollLimiter = new RateLimiter(30, 60_000L);

    public UnionHttpFacade(FederationJoinService federation, UnionBanService banService,
                           UnionDirectoryService directory, UnionDiscoveryService discovery,
                           UnionRepository repository) {
        this.federation = federation;
        this.banService = banService;
        this.directory = directory;
        this.discovery = discovery;
        this.repository = repository;
    }

    // ---- peer 机器面（wrapResult=false）----

    public PluginHttpResponse meta() {
        if (!metaLimiter.tryAcquire(now())) {
            return error(429, "auth.rateLimited", "请求过于频繁");
        }
        PluginHttpResponse response = PluginHttpResponse.rawJson(200, discovery.buildMeta());
        Map<String, String> headers = new LinkedHashMap<>(response.headers());
        headers.put(X_UNION_VERSION, UnionDiscoveryService.UNION_VERSION);
        return new PluginHttpResponse(response.status(), headers, response.contentType(), response.body(), response.wrapped());
    }

    public PluginHttpResponse joinsQuery(PluginHttpRequest request) {
        if (!joinLimiter.tryAcquire(now())) {
            return error(429, "auth.rateLimited", "请求过于频繁");
        }
        try {
            return federation.answerJoinQuery(query(request, "username"), query(request, "serverId"),
                    query(request, "aud"), now())
                    .map(body -> PluginHttpResponse.rawJson(200, body))
                    .orElseGet(PluginHttpResponse::noContent);
        } catch (UnionBusinessException e) {
            return error(e.status(), e.code(), e.getMessage());
        }
    }

    public PluginHttpResponse bans(PluginHttpRequest request) {
        if (!bansLimiter.tryAcquire(now())) {
            return error(429, "auth.rateLimited", "请求过于频繁");
        }
        long since;
        try {
            since = parseSince(query(request, "since"));
        } catch (IllegalArgumentException e) {
            return error(400, "invalid-request", e.getMessage());
        }
        List<Map<String, Object>> entries = repository.localBansAfter(discovery.siteCode().orElse(""), since, 500).stream()
                .map(this::peerBanView)
                .toList();
        String cursor = entries.isEmpty() ? String.valueOf(since) : String.valueOf(entries.getLast().get("seq"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cursor", cursor);
        body.put("bans", entries);
        body.put("count", entries.size());
        return PluginHttpResponse.rawJson(200, body);
    }

    public PluginHttpResponse enroll(PluginHttpRequest request) {
        if (!enrollLimiter.tryAcquire(now())) {
            return error(429, "auth.rateLimited", "注册尝试过于频繁");
        }
        try {
            Map<String, Object> body = JsonSupport.readMap(request.body());
            return PluginHttpResponse.rawJson(200, directory.enroll(
                    stringOf(body.get("origin")),
                    stringOf(body.get("enrollToken")),
                    body.get("note") == null ? null : String.valueOf(body.get("note")),
                    principalName(request.principal(), "peer-admin"), now()));
        } catch (UnionBusinessException e) {
            return error(e.status(), e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, "invalid-request", e.getMessage());
        }
    }

    public PluginHttpResponse bansReport(PluginHttpRequest request) {
        try {
            banService.applyReport(bearer(request), JsonSupport.readMap(request.body()), now());
            return PluginHttpResponse.noContent();
        } catch (UnionBusinessException e) {
            return error(e.status(), e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, "invalid-request", e.getMessage());
        }
    }

    // ---- admin 管理面（宿主信封）----

    public Object adminSettings() {
        Map<String, Object> body = new LinkedHashMap<>(directory.siteSettings());
        body.put("auditRetentionDays", retentionDays("auditDays", 30));
        body.put("joinRetentionDays", retentionDays("joinDays", 90));
        return body;
    }

    public Object saveSettings(PluginHttpRequest request) {
        Map<String, Object> body = JsonSupport.readMap(request.body());
        directory.saveSite(stringOf(body.get("siteCode")),
                body.get("siteName") == null ? null : String.valueOf(body.get("siteName")),
                principalName(request.principal(), "admin"));
        repository.saveSetting("retention", Map.of(
                "auditDays", retentionValue(body.get("auditRetentionDays"), 30),
                "joinDays", retentionValue(body.get("joinRetentionDays"), 90)));
        return adminSettings();
    }

    public Object issueEnrollToken(PluginHttpRequest request) {
        return directory.issueEnrollToken(principalName(request.principal(), "admin"), now());
    }

    public Object pagePeers(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", directory.listPeers(safePage, safeSize));
        body.put("total", directory.countPeers());
        return body;
    }

    public Object enrollPeer(PluginHttpRequest request) {
        Map<String, Object> body = JsonSupport.readMap(request.body());
        return directory.enroll(stringOf(body.get("origin")), stringOf(body.get("enrollToken")),
                body.get("note") == null ? null : String.valueOf(body.get("note")),
                principalName(request.principal(), "admin"), now());
    }

    public Object setPeerStatus(PluginHttpRequest request, String siteCode) {
        Map<String, Object> body = JsonSupport.readMap(request.body());
        return directory.peerView(directory.setPeerStatus(siteCode, stringOf(body.get("status")),
                principalName(request.principal(), "admin")));
    }

    public Object refreshPeer(String siteCode) {
        return directory.peerView(directory.refreshPeer(siteCode, principalName(null, "admin"), now()));
    }

    public void deletePeer(String siteCode) {
        directory.deletePeer(siteCode, principalName(null, "admin"));
    }

    public Object selfTestPeer(String siteCode) {
        return directory.selfTest(siteCode, now());
    }

    public Object pageBans(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", repository.pageActiveBans(safePage, safeSize, now()).stream().map(this::banView).toList());
        body.put("total", repository.activeBanCount(now()));
        return body;
    }

    public Object addBan(PluginHttpRequest request) {
        Map<String, Object> body = JsonSupport.readMap(request.body());
        return banView(banService.addBan(stringOf(body.get("name")), stringOf(body.get("reason")),
                body.get("evidence") == null ? null : String.valueOf(body.get("evidence")),
                discovery.siteCode().orElseThrow(() -> UnionBusinessException.invalid("请先配置本站 siteCode")),
                principalName(request.principal(), "admin"), now()));
    }

    public void liftBan(String name) {
        banService.liftBan(name, principalName(null, "admin"),
                discovery.siteCode().orElseThrow(() -> UnionBusinessException.invalid("请先配置本站 siteCode")), now());
    }

    public Object pullBans() {
        return Map.of("applied", banService.pullFromPeers(directory.trustedPeers(), now()));
    }

    /** 审计管理面可排序字段白名单；非法值回退 at。 */
    private static final java.util.Set<String> AUDIT_SORT_FIELDS =
            java.util.Set.of("at", "action", "target", "actor", "actorType", "detail");

    public Object pageAudit(int page, int size, String keyword, String sortBy, String order) {
        String safeSortBy = AUDIT_SORT_FIELDS.contains(sortBy) ? sortBy : "at";
        boolean descending = !"asc".equalsIgnoreCase(order);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", repository.pageAudit(page, size, blankToNull(keyword), safeSortBy, descending)
                .stream().map(this::auditView).toList());
        body.put("total", repository.auditCount(blankToNull(keyword)));
        return body;
    }

    /** 审计视图：时间/操作者/动作/目标/详情。 */
    private Map<String, Object> auditView(online.yudream.base.plugin.union.domain.aggregate.UnionAuditLog log) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.id());
        view.put("at", log.at());
        view.put("actorType", log.actorType());
        view.put("actor", log.actor());
        view.put("action", log.action());
        view.put("target", log.target());
        view.put("detail", log.detail());
        return view;
    }

    public void deleteAudit(String id) {
        repository.deleteAudit(id);
    }

    public void clearAudit() {
        repository.clearAudit();
    }

    public Object federationStats() {
        return federation.stats(now());
    }

    /** 跨站登录事件可排序字段白名单；非法值回退 recordedAt。 */
    private static final java.util.Set<String> JOIN_EVENT_SORT_FIELDS =
            java.util.Set.of("recordedAt", "siteCode", "profileName", "direction", "serverId");

    public Object federationLog(int page, int size, String site, String name, String sortBy, String order) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        String safeSortBy = JOIN_EVENT_SORT_FIELDS.contains(sortBy) ? sortBy : "recordedAt";
        boolean descending = !"asc".equalsIgnoreCase(order);
        // pageLog 返回的 total 已按 site/name 过滤，直接透传保证分页与筛选一致
        return federation.pageLog(blankToNull(site), blankToNull(name), safePage, safeSize,
                safeSortBy, descending, now());
    }

    // ---- helpers ----

    /** 站间账本最小视图：不泄露本地 evidence/actor/lift 操作人。 */
    private Map<String, Object> peerBanView(UnionBan ban) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("seq", String.valueOf(ban.seq()));
        view.put("name", ban.name());
        view.put("reason", ban.reason());
        view.put("sourceSite", ban.sourceSite());
        view.put("createdAt", ban.createdAt());
        view.put("expiresAt", ban.expiresAt());
        view.put("status", ban.status());
        return view;
    }

    private Map<String, Object> banView(UnionBan ban) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("seq", String.valueOf(ban.seq()));
        view.put("name", ban.name());
        view.put("nameLower", ban.nameLower());
        view.put("reason", ban.reason());
        view.put("sourceSite", ban.sourceSite());
        view.put("evidence", ban.evidence());
        view.put("createdBy", ban.createdBy());
        view.put("createdAt", ban.createdAt());
        view.put("expiresAt", ban.expiresAt());
        view.put("status", ban.status());
        view.put("liftedBy", ban.liftedBy());
        view.put("liftedAt", ban.liftedAt());
        return view;
    }

    private PluginHttpResponse error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("errorMessage", message);
        return PluginHttpResponse.rawJson(status, body);
    }

    private String query(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String bearer(PluginHttpRequest request) {
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase("authorization") && !entry.getValue().isEmpty()) {
                String authorization = entry.getValue().get(0);
                if (authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                    return authorization.substring(7).trim();
                }
            }
        }
        return "";
    }

    private long parseSince(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("since 必须为非负整数");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("since 必须为非负整数");
        }
    }

    private String principalName(PluginPrincipal principal, String fallback) {
        return principal != null && principal.userId() != null ? String.valueOf(principal.userId()) : fallback;
    }

    private long retentionDays(String key, long fallback) {
        return repository.setting("retention")
                .map(document -> document.get(key))
                .map(value -> {
                    try {
                        long parsed = Long.parseLong(String.valueOf(value));
                        return parsed >= 0 ? parsed : fallback;
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private long retentionValue(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed < 0) {
                throw new IllegalArgumentException("保留天数不能为负数");
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("保留天数必须为非负整数");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
