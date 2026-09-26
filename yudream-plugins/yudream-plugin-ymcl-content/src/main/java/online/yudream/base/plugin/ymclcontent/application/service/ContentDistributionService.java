package online.yudream.base.plugin.ymclcontent.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.ymclcontent.domain.ContentRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * YMCL 内容分发：公告（updates）/ 指引（guides）/ 在线内容（online）。
 * 数据落在 DocumentStore；公开端点供启动器 helpers/ymcl-content.ts 拉取。
 */
public class ContentDistributionService {

    public static final String RECORD_COLLECTION = "ymcl_content_records";
    public static final int API_VERSION = 1;
    public static final List<String> KINDS = List.of(
            ContentRecord.KIND_GUIDES,
            ContentRecord.KIND_UPDATES,
            ContentRecord.KIND_ONLINE);

    private final PluginDocumentStore documents;

    public ContentDistributionService(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public boolean isKnownKind(String kind) {
        return kind != null && KINDS.contains(kind.trim().toLowerCase());
    }

    /** 公开列表：仅启用记录，按 sort 升序、更新时间倒序。 */
    public List<ContentRecord> listVisibleByKind(String kind) {
        List<ContentRecord> records = new ArrayList<>();
        for (ContentRecord record : loadAll()) {
            if (record.kind() != null && record.kind().equalsIgnoreCase(kind)
                    && record.isPubliclyVisible()) {
                records.add(record);
            }
        }
        records.sort(ContentDistributionService::compareRecords);
        return records;
    }

    /** 全量目录：三个固定 kind 分组，每组仅启用记录。 */
    public Map<String, Object> catalog() {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (String kind : KINDS) {
            grouped.put(kind, new ArrayList<>());
        }
        List<ContentRecord> all = new ArrayList<>(loadAll());
        all.sort(ContentDistributionService::compareRecords);
        for (ContentRecord record : all) {
            if (!record.isPubliclyVisible()) {
                continue;
            }
            List<Map<String, Object>> bucket = grouped.get(record.kind() == null ? "" : record.kind().toLowerCase());
            if (bucket != null) {
                bucket.add(record.toPublicRecord());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiVersion", API_VERSION);
        for (String kind : KINDS) {
            payload.put(kind, grouped.get(kind));
        }
        return payload;
    }

    /** 管理端分页列表：kind 可空=全部。返回 records + total。 */
    public Map<String, Object> listAdmin(String kind, int page, int size) {
        List<ContentRecord> filtered = new ArrayList<>();
        for (ContentRecord record : loadAll()) {
            if (kind == null || kind.isBlank()
                    || (record.kind() != null && record.kind().equalsIgnoreCase(kind))) {
                filtered.add(record);
            }
        }
        filtered.sort(ContentDistributionService::compareRecords);
        int safeSize = Math.max(1, Math.min(200, size));
        int total = filtered.size();
        int pageCount = (total + safeSize - 1) / safeSize;
        int safePage = Math.min(Math.max(1, page), Math.max(1, pageCount));
        int from = (safePage - 1) * safeSize;
        int to = Math.min(from + safeSize, total);
        List<Map<String, Object>> records = new ArrayList<>();
        for (ContentRecord record : filtered.subList(from, to)) {
            records.add(record.toAdminRecord());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("records", records);
        payload.put("total", total);
        payload.put("page", safePage);
        payload.put("size", safeSize);
        return payload;
    }

    public Optional<ContentRecord> findRecord(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(RECORD_COLLECTION, id).map(ContentRecord::fromMap);
    }

    /** 创建或更新；id 缺省时生成。标题必填，kind 必须是已知分类，url 必须是绝对 http(s)。 */
    public ContentRecord saveRecord(Map<String, Object> payload) {
        String title = text(payload.get("title"));
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        String kind = text(payload.get("kind"));
        if (!isKnownKind(kind)) {
            throw new IllegalArgumentException("kind must be one of guides/updates/online");
        }
        kind = kind.trim().toLowerCase();
        String url = normalizeUrl(text(payload.get("url")));
        String coverUrl = normalizeUrl(text(payload.get("coverUrl")));

        String id = text(payload.get("id"));
        ContentRecord existing = id == null || id.isBlank() ? null : findRecord(id).orElse(null);
        String recordId = existing == null ? newRecordId() : existing.id();
        long sort = payload.containsKey("sort") ? longOf(payload.get("sort"))
                : existing == null ? 0L : existing.sort();
        boolean enabled = payload.get("enabled") == null
                ? existing == null || existing.enabled()
                : boolOf(payload.get("enabled"));

        ContentRecord record = new ContentRecord(
                recordId,
                kind,
                title.trim(),
                text(payload.get("summary")),
                text(payload.get("body")),
                coverUrl,
                url,
                text(payload.get("meta")),
                sort,
                enabled,
                System.currentTimeMillis());
        persist(record);
        return record;
    }

    public boolean deleteRecord(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        if (findRecord(id).isEmpty()) {
            return false;
        }
        documents.delete(RECORD_COLLECTION, id);
        return true;
    }

    private List<ContentRecord> loadAll() {
        List<ContentRecord> records = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(RECORD_COLLECTION, page, 200);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> doc : batch) {
                records.add(ContentRecord.fromMap(doc));
            }
            if (batch.size() < 200) {
                break;
            }
            page++;
        }
        return records;
    }

    private void persist(ContentRecord record) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", record.id());
        doc.put("kind", record.kind());
        doc.put("title", record.title());
        doc.put("summary", record.summary());
        doc.put("body", record.body());
        doc.put("coverUrl", record.coverUrl());
        doc.put("url", record.url());
        doc.put("meta", record.meta());
        doc.put("sort", record.sort());
        doc.put("enabled", record.enabled());
        doc.put("updatedAtMillis", record.updatedAtMillis());
        documents.save(RECORD_COLLECTION, record.id(), doc);
    }

    private static String newRecordId() {
        return "cnt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!(value.startsWith("http://") || value.startsWith("https://"))) {
            throw new IllegalArgumentException("url must be an absolute http(s) URL");
        }
        return value;
    }

    private static int compareRecords(ContentRecord left, ContentRecord right) {
        int cmp = Long.compare(left.sort(), right.sort());
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(right.updatedAtMillis(), left.updatedAtMillis());
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean boolOf(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
