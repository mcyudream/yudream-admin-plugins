package online.yudream.base.plugin.mcpanel.infrastructure.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.domain.repo.TrashRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.TrashRecord;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 回收站记录仓储。回收记录量级 = 已删除实例数，关键词过滤与切片在本层内存完成
 * （与节点四态筛选同一模式）；文档单页 200 上限按 PAGE_SIZE 翻页取尽。
 */
public class DocumentTrashRepository implements TrashRepository {

    static final String COLLECTION = "mcpanel_trash";
    private static final int PAGE_SIZE = 200;

    private final PluginDocumentStore documents;
    private final ObjectMapper mapper;

    public DocumentTrashRepository(PluginDocumentStore documents, ObjectMapper mapper) {
        this.documents = documents;
        this.mapper = mapper;
    }

    @Override
    public TrashRecord save(TrashRecord record) {
        documents.save(COLLECTION, record.trashId(), toDocument(record));
        return record;
    }

    @Override
    public Optional<TrashRecord> findById(String trashId) {
        return documents.findById(COLLECTION, trashId).map(this::toRecord);
    }

    @Override
    public PageResult<TrashRecord> page(int page, int size, String keyword) {
        List<TrashRecord> all = findAllPaged();
        if (keyword != null && !keyword.isBlank()) {
            String needle = keyword.trim().toLowerCase(Locale.ROOT);
            all = all.stream().filter(record ->
                    record.instanceName() != null && record.instanceName().toLowerCase(Locale.ROOT).contains(needle)
                            || record.nodeId() != null && record.nodeId().toLowerCase(Locale.ROOT).contains(needle)
                            || record.instanceId() != null && record.instanceId().toLowerCase(Locale.ROOT).contains(needle)
            ).toList();
        }
        all = all.stream().sorted((a, b) -> Long.compare(b.deletedAtMs(), a.deletedAtMs())).toList();
        int pageNo = page < 1 ? 1 : page;
        int pageSize = size < 1 ? 10 : Math.min(size, 100);
        int from = Math.min((pageNo - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new PageResult<>(all.subList(from, to), all.size(), pageNo, pageSize);
    }

    @Override
    public boolean existsByNodeId(String nodeId) {
        return findAllPaged().stream().anyMatch(record -> record.nodeId() != null && record.nodeId().equals(nodeId));
    }

    @Override
    public void delete(String trashId) {
        documents.delete(COLLECTION, trashId);
    }

    /** 单页上限 200 静默截断：必须按 200 翻页取尽（见宿主文档存储限制）。 */
    private List<TrashRecord> findAllPaged() {
        List<TrashRecord> all = new ArrayList<>();
        int page = 1;
        List<TrashRecord> batch;
        do {
            batch = documents.findAll(COLLECTION, page, PAGE_SIZE).stream().map(this::toRecord).toList();
            all.addAll(batch);
            page++;
        } while (batch.size() == PAGE_SIZE);
        return all;
    }

    private Map<String, Object> toDocument(TrashRecord record) {
        Map<String, Object> raw = mapper.convertValue(record, new TypeReference<Map<String, Object>>() {
        });
        // 深度 null 剔除（沙盒文档存储 Map.copyOf 对嵌套 null 值也会裸 NPE，
        // specSnapshot 里可能带 lastExitCode=null 等字段）。
        Map<String, Object> stripped = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> {
            Object cleaned = stripNulls(value);
            if (cleaned != null) {
                stripped.put(key, cleaned);
            }
        });
        return stripped;
    }

    private static Object stripNulls(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> {
                Object inner = stripNulls(item);
                if (inner != null) {
                    cleaned.put(String.valueOf(key), inner);
                }
            });
            return cleaned;
        }
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).toList();
        }
        return value;
    }

    private TrashRecord toRecord(Map<String, Object> document) {
        return mapper.convertValue(document, TrashRecord.class);
    }
}
