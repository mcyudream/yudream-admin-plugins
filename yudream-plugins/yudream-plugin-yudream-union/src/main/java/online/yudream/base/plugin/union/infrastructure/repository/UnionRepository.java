package online.yudream.base.plugin.union.infrastructure.repository;

import online.yudream.base.plugin.union.domain.aggregate.UnionAuditLog;
import online.yudream.base.plugin.union.domain.aggregate.UnionBan;
import online.yudream.base.plugin.union.domain.aggregate.UnionEnrollToken;
import online.yudream.base.plugin.union.domain.aggregate.UnionJoinEvent;
import online.yudream.base.plugin.union.domain.aggregate.UnionPeer;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 联合认证仓储（PluginDocumentStore；分页全扫上限 200 与 authlib 同约定）。 */
public class UnionRepository {

    public static final String PEERS = "union_peers";
    public static final String BANS = "union_bans";
    public static final String BAN_OVERRIDES = "union_ban_overrides";
    public static final String ENROLL_TOKENS = "union_enroll_tokens";
    public static final String AUDIT_LOGS = "union_audit_logs";
    public static final String JOIN_EVENTS = "union_join_events";
    public static final String SETTINGS = "settings";

    private static final int SCAN_PAGE_SIZE = 200;

    private final PluginDocumentStore documents;

    public UnionRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    // ---- settings ----

    public Optional<Map<String, Object>> setting(String id) {
        return documents.findById(SETTINGS, id);
    }

    public Map<String, Object> saveSetting(String id, Map<String, Object> document) {
        return documents.save(SETTINGS, id, document);
    }

    public long banCursor() {
        return setting("ban-cursor").map(document -> number(document, "seq")).orElse(0L);
    }

    public long saveBanCursor(long seq) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("seq", seq);
        saveSetting("ban-cursor", document);
        return seq;
    }

    /**
     * CAS 保留下一条本站账本 seq。DocumentStore 只有 updateIfFieldAtMost，首次创建由
     * 单实例插件初始化；之后每次都以 cursor 当前值作为 CAS 前置条件，避免并发写覆盖。
     */
    public synchronized long reserveNextBanSeq() {
        for (int attempts = 0; attempts < 32; attempts++) {
            Optional<Map<String, Object>> currentDoc = setting("ban-cursor");
            if (currentDoc.isEmpty()) {
                saveBanCursor(0L);
                continue;
            }
            long current = number(currentDoc.get(), "seq");
            long next = current + 1L;
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("seq", next);
            if (documents.updateIfFieldAtMost(SETTINGS, "ban-cursor", "seq", current, update)) {
                return next;
            }
        }
        throw new IllegalStateException("联合封禁账本序号并发保留失败，请稍后重试");
    }

    // ---- peers ----

    public UnionPeer savePeer(UnionPeer peer) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("siteName", peer.siteName());
        document.put("origin", peer.origin());
        document.put("apiRoot", peer.apiRoot());
        document.put("textureBaseUrl", peer.textureBaseUrl());
        document.put("publicKeyPem", peer.publicKeyPem());
        document.put("backupPublicKeyPem", peer.backupPublicKeyPem());
        document.put("tlsCertSha256", peer.tlsCertSha256());
        document.put("peerSecretSha256", peer.peerSecretSha256());
        document.put("status", peer.status());
        document.put("addedBy", peer.addedBy());
        document.put("addedAt", peer.addedAt());
        document.put("note", peer.note());
        document.put("banCursor", peer.banCursor());
        return toPeer(peer.id(), documents.save(PEERS, peer.id(), document));
    }

    public Optional<UnionPeer> findPeer(String siteCode) {
        return documents.findById(PEERS, siteCode).map(document -> toPeer(siteCode, document));
    }

    public List<UnionPeer> allPeers() {
        List<UnionPeer> peers = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(PEERS, page, SCAN_PAGE_SIZE);
            for (Map<String, Object> document : batch) {
                peers.add(toPeer(string(document, "id"), document));
            }
            if (batch.size() < SCAN_PAGE_SIZE) {
                return peers;
            }
            page++;
        }
    }

    public void deletePeer(String siteCode) {
        documents.delete(PEERS, siteCode);
    }

    // ---- bans ----

    public UnionBan saveBan(UnionBan ban) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("seq", ban.seq());
        document.put("name", ban.name());
        document.put("nameLower", ban.nameLower());
        document.put("reason", ban.reason());
        document.put("sourceSite", ban.sourceSite());
        document.put("evidence", ban.evidence());
        document.put("createdBy", ban.createdBy());
        document.put("createdAt", ban.createdAt());
        document.put("expiresAt", ban.expiresAt());
        document.put("status", ban.status());
        document.put("liftedBy", ban.liftedBy());
        document.put("liftedAt", ban.liftedAt());
        documents.save(BANS, String.valueOf(ban.seq()), document);
        return ban;
    }

    public Optional<UnionBan> findBan(long seq) {
        return documents.findById(BANS, String.valueOf(seq)).map(document -> toBan(number(document, "seq"), document));
    }

    public List<UnionBan> bansByName(String nameLower) {
        return allByField(BANS, "nameLower", nameLower).stream()
                .map(document -> toBan(number(document, "seq"), document))
                .toList();
    }

    public List<UnionBan> pageBans(int page, int size) {
        return documents.findAll(BANS, page, size).stream()
                .map(document -> toBan(number(document, "seq"), document))
                .toList();
    }

    /** 当前生效裁决：每个 (nameLower, sourceSite) 取最新事件，排除本地 override/过期项。 */
    public List<UnionBan> pageActiveBans(int page, int size, long now) {
        Map<String, UnionBan> latest = latestBanEvents();
        return latest.values().stream()
                .filter(event -> findBanOverride(event.nameLower()).isEmpty())
                .filter(event -> event.activeAt(now))
                .sorted(java.util.Comparator.comparingLong(UnionBan::seq).reversed())
                .skip((long) Math.max(0, page - 1) * Math.max(1, size))
                .limit(Math.max(1, size))
                .toList();
    }

    public long activeBanCount(long now) {
        return latestBanEvents().values().stream()
                .filter(event -> findBanOverride(event.nameLower()).isEmpty())
                .filter(event -> event.activeAt(now))
                .count();
    }

    private Map<String, UnionBan> latestBanEvents() {
        Map<String, UnionBan> latest = new LinkedHashMap<>();
        for (UnionBan event : allBanEvents()) {
            String key = String.valueOf(event.nameLower()) + "\u0000" + String.valueOf(event.sourceSite());
            UnionBan previous = latest.get(key);
            if (previous == null || event.seq() > previous.seq()) {
                latest.put(key, event);
            }
        }
        return latest;
    }

    private List<UnionBan> allBanEvents() {
        List<UnionBan> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(BANS, page, SCAN_PAGE_SIZE);
            result.addAll(batch.stream().map(document -> toBan(number(document, "seq"), document)).toList());
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    /**
     * 账本增量：DocumentStore 无范围/排序查询，故分页全扫后按 seq 严格升序返回。
     * cursor 只能推进到实际交付的最后一条，调用方不得跳过未交付记录。
     */
    public List<UnionBan> bansAfter(long since, int limit) {
        List<UnionBan> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(BANS, page, SCAN_PAGE_SIZE);
            result.addAll(batch.stream()
                    .map(document -> toBan(number(document, "seq"), document))
                    .filter(ban -> ban.seq() > since)
                    .toList());
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
            page++;
        }
        return result.stream().sorted(java.util.Comparator.comparingLong(UnionBan::seq)).limit(limit).toList();
    }

    /** 仅导出本站签发的裁决，避免未签名的跨站传递造成隐式传递信任。 */
    public List<UnionBan> localBansAfter(String localSiteCode, long since, int limit) {
        if (localSiteCode == null || localSiteCode.isBlank()) {
            return List.of();
        }
        return bansAfter(since, Integer.MAX_VALUE).stream()
                .filter(ban -> localSiteCode.equals(ban.sourceSite()))
                .limit(limit)
                .toList();
    }

    public long banCount() {
        return documents.count(BANS);
    }

    /** 本站管理员对本站/外来封禁的本地解除覆盖（防拉取合并复活）。键 = nameLower。 */
    public void saveBanOverride(String nameLower, String liftedBy, long liftedAt) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("liftedBy", liftedBy);
        document.put("liftedAt", liftedAt);
        documents.save(BAN_OVERRIDES, nameLower, document);
    }

    public Optional<Map<String, Object>> findBanOverride(String nameLower) {
        return documents.findById(BAN_OVERRIDES, nameLower);
    }

    /** 显式重新封禁时清除此前管理员的解除覆盖，使新裁决立即生效。 */
    public void deleteBanOverride(String nameLower) {
        documents.delete(BAN_OVERRIDES, nameLower);
    }

    // ---- enroll tokens ----

    public void saveEnrollToken(UnionEnrollToken token) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("createdBy", token.createdBy());
        document.put("createdAt", token.createdAt());
        document.put("expiresAt", token.expiresAt());
        document.put("consumed", token.consumed() ? 1L : 0L);
        document.put("consumedAt", token.consumedAt());
        documents.save(ENROLL_TOKENS, token.id(), document);
    }

    public Optional<UnionEnrollToken> findEnrollToken(String digest) {
        return documents.findById(ENROLL_TOKENS, digest).map(this::toEnrollToken);
    }

    /** 一次性 CAS 消费：仅当 consumed 仍为 0 时置 1（并发只赢一次，完整文档避免丢字段）。 */
    public boolean consumeEnrollToken(UnionEnrollToken token, long consumedAt) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("createdBy", token.createdBy());
        update.put("createdAt", token.createdAt());
        update.put("expiresAt", token.expiresAt());
        update.put("consumed", 1L);
        update.put("consumedAt", consumedAt);
        return documents.updateIfFieldAtMost(ENROLL_TOKENS, token.id(), "consumed", 0, update);
    }

    // ---- audit ----

    public void saveAudit(UnionAuditLog log) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("actorType", log.actorType());
        document.put("actor", log.actor());
        document.put("action", log.action());
        document.put("target", log.target());
        document.put("detail", log.detail());
        document.put("at", log.at());
        documents.save(AUDIT_LOGS, log.id(), document);
    }

    public List<UnionAuditLog> pageAudit(int page, int size, String keyword, String sortBy, boolean descending) {
        List<UnionAuditLog> logs = new ArrayList<>();
        int sourcePage = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(AUDIT_LOGS, sourcePage, SCAN_PAGE_SIZE);
            logs.addAll(batch.stream().map(this::toAudit)
                    .filter(log -> keyword == null || keyword.isBlank() || matchesAudit(log, keyword))
                    .toList());
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
            sourcePage++;
        }
        Comparator<UnionAuditLog> primary = auditComparator(sortBy);
        Comparator<UnionAuditLog> tiebreak = Comparator.comparingLong(UnionAuditLog::at)
                .thenComparing(UnionAuditLog::id);
        logs.sort(descending ? primary.reversed().thenComparing(tiebreak.reversed())
                : primary.thenComparing(tiebreak));
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(200, size));
        int from = Math.min((safePage - 1) * safeSize, logs.size());
        int to = Math.min(from + safeSize, logs.size());
        return new ArrayList<>(logs.subList(from, to));
    }

    /** 管理面排序字段；非法值回退到时间。 */
    private Comparator<UnionAuditLog> auditComparator(String sortBy) {
        return switch (sortBy == null ? "at" : sortBy) {
            case "action" -> Comparator.comparing(log -> nullableText(log.action()));
            case "target" -> Comparator.comparing(log -> nullableText(log.target()));
            case "actor" -> Comparator.comparing(log -> nullableText(log.actor()));
            case "actorType" -> Comparator.comparing(log -> nullableText(log.actorType()));
            case "detail" -> Comparator.comparing(log -> nullableText(log.detail()));
            default -> Comparator.comparingLong(UnionAuditLog::at);
        };
    }

    private static String nullableText(String value) {
        return value == null ? "" : value;
    }

    public long auditCount(String keyword) {
        int page = 1;
        long count = 0;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(AUDIT_LOGS, page, SCAN_PAGE_SIZE);
            count += batch.stream().map(this::toAudit)
                    .filter(log -> keyword == null || keyword.isBlank() || matchesAudit(log, keyword))
                    .count();
            if (batch.size() < SCAN_PAGE_SIZE) {
                return count;
            }
            page++;
        }
    }

    private boolean matchesAudit(UnionAuditLog log, String keyword) {
        String needle = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        return contains(log.actorType(), needle) || contains(log.actor(), needle)
                || contains(log.action(), needle) || contains(log.target(), needle)
                || contains(log.detail(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    public Optional<UnionAuditLog> findAudit(String id) {
        return documents.findById(AUDIT_LOGS, id).map(this::toAudit);
    }

    public void deleteAudit(String id) {
        documents.delete(AUDIT_LOGS, id);
    }

    public void clearAudit() {
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(AUDIT_LOGS, 1, SCAN_PAGE_SIZE);
            if (batch.isEmpty()) {
                return;
            }
            for (Map<String, Object> document : batch) {
                documents.delete(AUDIT_LOGS, String.valueOf(document.get("id")));
            }
        }
    }

    /** 审计保留清理：先完整扫描，再逐条删除早于 cutoff 的记录，避免分页删除跳过相邻记录。 */
    public long purgeAuditBefore(long cutoff) {
        List<String> expiredIds = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(AUDIT_LOGS, page, SCAN_PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> document : batch) {
                UnionAuditLog log = toAudit(document);
                if (log.at() > 0 && log.at() < cutoff) {
                    expiredIds.add(log.id());
                }
            }
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
            page++;
        }
        expiredIds.forEach(id -> documents.delete(AUDIT_LOGS, id));
        return expiredIds.size();
    }

    // ---- 跨站登录事件（可溯源账本）----

    public void saveJoinEvent(UnionJoinEvent event) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("direction", event.direction());
        document.put("siteCode", event.siteCode());
        document.put("profileId", event.profileId());
        document.put("profileName", event.profileName());
        document.put("serverId", event.serverId());
        document.put("issuerKeyId", event.issuerKeyId());
        document.put("recordedAt", event.recordedAt());
        documents.save(JOIN_EVENTS, event.id(), document);
    }

    public List<UnionJoinEvent> allJoinEvents() {
        List<UnionJoinEvent> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(JOIN_EVENTS, page, SCAN_PAGE_SIZE);
            result.addAll(batch.stream().map(this::toJoinEvent).toList());
            if (batch.size() < SCAN_PAGE_SIZE) {
                return result;
            }
            page++;
        }
    }

    /** 事件按记录时间倒序分页；site/name 为可选过滤（name 前缀匹配规范化角色名），排序字段走白名单。 */
    public List<UnionJoinEvent> pageJoinEvents(String siteCode, String nameLower, int page, int size,
                                               String sortBy, boolean descending) {
        List<UnionJoinEvent> events = new ArrayList<>(allJoinEvents());
        if (siteCode != null && !siteCode.isBlank()) {
            events.removeIf(event -> !siteCode.equals(event.siteCode()));
        }
        if (nameLower != null && !nameLower.isBlank()) {
            events.removeIf(event -> event.profileName() == null
                    || !event.profileName().toLowerCase().startsWith(nameLower));
        }
        Comparator<UnionJoinEvent> primary = joinEventComparator(sortBy);
        Comparator<UnionJoinEvent> tiebreak = Comparator.comparingLong(UnionJoinEvent::recordedAt)
                .thenComparing(UnionJoinEvent::id);
        events.sort(descending ? primary.reversed().thenComparing(tiebreak.reversed())
                : primary.thenComparing(tiebreak));
        int from = Math.min((page - 1) * size, events.size());
        int to = Math.min(from + size, events.size());
        return new ArrayList<>(events.subList(from, to));
    }

    /** 跨站登录事件排序字段；非法值回退到记录时间。 */
    private Comparator<UnionJoinEvent> joinEventComparator(String sortBy) {
        return switch (sortBy == null ? "recordedAt" : sortBy) {
            case "siteCode" -> Comparator.comparing(event -> nullableText(event.siteCode()));
            case "profileName" -> Comparator.comparing(event -> nullableText(event.profileName()));
            case "direction" -> Comparator.comparing(event -> nullableText(event.direction()));
            case "serverId" -> Comparator.comparing(event -> nullableText(event.serverId()));
            default -> Comparator.comparingLong(UnionJoinEvent::recordedAt);
        };
    }

    public long joinEventCount() {
        return documents.count(JOIN_EVENTS);
    }

    /** 事件保留清理：早于 cutoff 的记录删除，返回删除数。 */
    public long purgeJoinEventsBefore(long cutoff) {
        long deleted = 0;
        for (UnionJoinEvent event : allJoinEvents()) {
            if (event.recordedAt() < cutoff) {
                documents.delete(JOIN_EVENTS, event.id());
                deleted++;
            }
        }
        return deleted;
    }

    private UnionJoinEvent toJoinEvent(Map<String, Object> document) {
        return new UnionJoinEvent(
                string(document, "id"),
                string(document, "direction"),
                string(document, "siteCode"),
                string(document, "profileId"),
                string(document, "profileName"),
                string(document, "serverId"),
                string(document, "issuerKeyId"),
                number(document, "recordedAt"));
    }

    // ---- converters ----

    private UnionPeer toPeer(String id, Map<String, Object> document) {
        return new UnionPeer(
                id,
                string(document, "siteName"),
                string(document, "origin"),
                string(document, "apiRoot"),
                string(document, "textureBaseUrl"),
                string(document, "publicKeyPem"),
                string(document, "backupPublicKeyPem"),
                string(document, "tlsCertSha256"),
                string(document, "peerSecretSha256"),
                string(document, "status"),
                string(document, "addedBy"),
                number(document, "addedAt"),
                string(document, "note"),
                number(document, "banCursor"));
    }

    private UnionBan toBan(long seq, Map<String, Object> document) {
        return new UnionBan(
                seq,
                string(document, "name"),
                string(document, "nameLower"),
                string(document, "reason"),
                string(document, "sourceSite"),
                string(document, "evidence"),
                string(document, "createdBy"),
                number(document, "createdAt"),
                document.get("expiresAt") == null ? null : number(document, "expiresAt"),
                string(document, "status"),
                string(document, "liftedBy"),
                document.get("liftedAt") == null ? null : number(document, "liftedAt"));
    }

    private UnionEnrollToken toEnrollToken(Map<String, Object> document) {
        return new UnionEnrollToken(
                string(document, "id"),
                string(document, "createdBy"),
                number(document, "createdAt"),
                number(document, "expiresAt"),
                number(document, "consumed") > 0,
                document.get("consumedAt") == null ? null : number(document, "consumedAt"));
    }

    private UnionAuditLog toAudit(Map<String, Object> document) {
        return new UnionAuditLog(
                string(document, "id"),
                string(document, "actorType"),
                string(document, "actor"),
                string(document, "action"),
                string(document, "target"),
                string(document, "detail"),
                number(document, "at"));
    }

    private List<Map<String, Object>> allByField(String collection, String field, Object value) {
        List<Map<String, Object>> records = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findByField(collection, field, value, page, SCAN_PAGE_SIZE);
            records.addAll(batch);
            if (batch.size() < SCAN_PAGE_SIZE) {
                return records;
            }
            page++;
        }
    }

    private String string(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private long number(Map<String, Object> document, String key) {
        if (document.get(key) instanceof Number number) {
            return number.longValue();
        }
        Object value = document.get(key);
        return value == null || String.valueOf(value).isBlank() ? 0L : Long.parseLong(String.valueOf(value));
    }
}
