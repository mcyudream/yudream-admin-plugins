package online.yudream.base.plugin.skin.infrastructure.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.skin.domain.aggregate.OfficialBind;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 正版绑定仓储与设置（PluginDocumentStore；gaea：states 一次性、设置独立文档）。 */
public class OfficialBindDocumentRepository {

    public static final String BINDS = "official_binds";
    public static final String STATES = "official_bind_states";
    public static final String SETTINGS_DOC = "official-bind";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PluginDocumentStore documents;

    public OfficialBindDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    // ---- binds ----

    public OfficialBind saveBind(OfficialBind bind) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("ownerId", bind.ownerId());
        document.put("officialNameLast", bind.officialNameLast());
        document.put("verificationMethod", bind.verificationMethod());
        document.put("xuid", bind.xuid());
        document.put("msRefreshStored", bind.msRefreshStored());
        document.put("status", bind.status());
        document.put("verifiedAt", bind.verifiedAt());
        document.put("createdAt", bind.createdAt());
        document.put("unboundAt", bind.unboundAt());
        documents.save(BINDS, bind.officialUuid(), document);
        return bind;
    }

    public Optional<OfficialBind> findBindByUuid(String officialUuid) {
        return documents.findById(BINDS, normalize(officialUuid)).map(this::toBind);
    }

    public List<OfficialBind> findBindsByOwner(String ownerId) {
        return pageAll(BINDS, "ownerId", ownerId).stream().map(this::toBind).toList();
    }

    public void deleteBind(String officialUuid) {
        documents.delete(BINDS, normalize(officialUuid));
    }

    /** 全站已占用官方 UUID 检查（防多人绑同一账号）。 */
    public List<OfficialBind> findBindsByUuidAcrossOwners(String officialUuid) {
        return findBindByUuid(officialUuid).map(List::of).orElseGet(List::of);
    }

    // ---- bind states（一次性，10 分钟 TTL）----

    public void saveState(String state, String ownerId, String codeVerifier, long expiresAt) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("ownerId", ownerId);
        document.put("codeVerifier", codeVerifier);
        document.put("expiresAt", expiresAt);
        document.put("consumed", 0L);
        documents.save(STATES, state, document);
    }

    /**
     * 原子消费 OAuth state：先返回原始 payload 再将 consumed 置 1；并发回调只有一方可继续。
     * DocumentStore 的 CAS 更新要求提交完整文档，避免更新时丢失 owner/verifier/expiry。
     */
    public Optional<Map<String, Object>> consumeState(String state, long now) {
        Optional<Map<String, Object>> existing = documents.findById(STATES, state);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> document = existing.get();
        long expiresAt = number(document.get("expiresAt"));
        if (expiresAt <= now || number(document.get("consumed")) > 0L) {
            return Optional.empty();
        }
        Map<String, Object> consumed = new LinkedHashMap<>();
        consumed.put("ownerId", text(document.get("ownerId")));
        consumed.put("expiresAt", expiresAt);
        consumed.put("consumed", 1L);
        consumed.put("consumedAt", now);
        if (!documents.updateIfFieldAtMost(STATES, state, "consumed", 0L, consumed)) {
            return Optional.empty();
        }
        return Optional.of(document);
    }

    public void deleteState(String state) {
        documents.delete(STATES, state);
    }

    // ---- settings ----

    public Map<String, Object> settings() {
        return documents.findById("settings", SETTINGS_DOC)
                .map(document -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("enabled", bool(document.get("enabled")));
                    view.put("clientId", text(document.get("clientId")));
                    return view;
                })
                .orElseGet(() -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("enabled", false);
                    view.put("clientId", "");
                    return view;
                });
    }

    public Map<String, Object> saveSettings(boolean enabled, String clientId) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("enabled", enabled);
        document.put("clientId", clientId == null ? "" : clientId.trim());
        documents.save("settings", SETTINGS_DOC, document);
        return settings();
    }

    // ---- helpers ----

    private OfficialBind toBind(Map<String, Object> document) {
        return new OfficialBind(
                text(document.get("id")),
                text(document.get("ownerId")),
                text(document.get("officialNameLast")),
                text(document.get("verificationMethod")),
                text(document.get("xuid")),
                bool(document.get("msRefreshStored")),
                text(document.get("status")),
                number(document.get("verifiedAt")),
                number(document.get("createdAt")),
                document.get("unboundAt") == null ? null : number(document.get("unboundAt"))
        );
    }

    private List<Map<String, Object>> pageAll(String collection, String field, String value) {
        List<Map<String, Object>> records = new java.util.ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findByField(collection, field, value, page, 200);
            records.addAll(batch);
            if (batch.size() < 200) {
                return records;
            }
            page++;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null || String.valueOf(value).isBlank() ? 0L : Long.parseLong(String.valueOf(value));
    }

    private static String normalize(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase();
    }

    /** 仅解析用（避免暴露 Jackson 类型到领域层）。 */
    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败：" + e.getMessage(), e);
        }
    }

    public static String originOf(String configured) {
        try {
            URI uri = URI.create(configured.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "";
            }
            String origin = uri.getScheme() + "://" + uri.getHost();
            if (uri.getPort() >= 0) {
                origin += ":" + uri.getPort();
            }
            return origin;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
