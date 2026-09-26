package online.yudream.base.plugin.mcpanel.infrastructure.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 文档存储实例仓储：分页遍历取尽（200/页）。
 *
 * 并发模型（单 JVM 写者边界，见领域仓储注释）：
 * - 文档携带仓储管理的单调版本号 rev（不出现在聚合里）；save = rev+1 的权威写，
 *   mutateState = 「读最新 → 纯函数变换 → updateIfFieldAtMost(rev)」的 CAS 循环；
 * - CAS 失败即说明并发写者先落库（或文档已删），自动以最新文档重试，最多
 *   {@link #MAX_CAS_ATTEMPTS} 轮；文档已删返回 false，杜绝事件迟到复活记录；
 * - 宿主存储未实现 updateIfFieldAtMost 时（SPI 默认恒 false），mutateState 返回
 *   false 并保持现状——宁可丢一次状态刷新也不退化为 find+save 的旧读覆盖。
 */
public class DocumentMcpanelInstanceRepository implements McpanelInstanceRepository {

    static final String COLLECTION = "mcpanel_instances";
    private static final int PAGE_SIZE = 200;
    /** CAS 重试上限：超过说明并发冲突持续，放弃本次（事件流语义允许下一条事件兜底）。 */
    private static final int MAX_CAS_ATTEMPTS = 5;
    static final String FIELD_REV = "rev";

    private final PluginDocumentStore documents;
    private final ObjectMapper mapper;

    public DocumentMcpanelInstanceRepository(PluginDocumentStore documents, ObjectMapper mapper) {
        this.documents = documents;
        this.mapper = mapper;
    }

    @Override
    public void save(McpanelInstance instance) {
        Map<String, Object> document = toDocument(instance);
        documents.save(COLLECTION, instance.id(), document);
    }

    @Override
    public Optional<McpanelInstance> findById(String id) {
        return documents.findById(COLLECTION, id).map(this::toInstance);
    }

    @Override
    public List<McpanelInstance> findAll() {
        List<McpanelInstance> all = new ArrayList<>();
        int page = 1;
        List<McpanelInstance> batch;
        do {
            batch = documents.findAll(COLLECTION, page, PAGE_SIZE).stream().map(this::toInstance).toList();
            all.addAll(batch);
            page++;
        } while (batch.size() == PAGE_SIZE);
        return all;
    }

    @Override
    public void delete(String id) {
        documents.delete(COLLECTION, id);
    }

    @Override
    public boolean mutateState(String id, UnaryOperator<McpanelInstance> mutator) {
        return mutate(id, mutator);
    }

    @Override
    public boolean mutate(String id, UnaryOperator<McpanelInstance> mutator) {
        if (id == null || id.isBlank() || mutator == null) {
            return false;
        }
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            Optional<Map<String, Object>> raw = documents.findById(COLLECTION, id);
            if (raw.isEmpty()) {
                // 已删除：返回 false，调用方不得复活记录。
                return false;
            }
            long rev = revision(raw.get());
            McpanelInstance updated;
            McpanelInstance updatedBase;
            try {
                updatedBase = toInstance(raw.get());
                updated = mutator.apply(updatedBase);
            } catch (RuntimeException error) {
                // mutator 只做纯变换：抛错视为本次迁移放弃，不落半截状态。
                return false;
            }
            if (updated == null) {
                return false;
            }
            if (updated == updatedBase) {
                // mutator 以同一引用返回表示无变化：视为成功，不产生写放大。
                return true;
            }
            Map<String, Object> document = toDocument(updated);
            document.put(FIELD_REV, rev + 1);
            if (documents.updateIfFieldAtMost(COLLECTION, id, FIELD_REV, rev, document)) {
                return true;
            }
        }
        return false;
    }

    // ---------- 内部 ----------

    private Map<String, Object> toDocument(McpanelInstance instance) {
        Map<String, Object> document = mapper.convertValue(instance,
                new TypeReference<Map<String, Object>>() {
                });
        // 文档存储不接受 null 值：未知退出码以 -1 哨兵落库（读取侧由聚合还原语义）。
        document.replaceAll((key, value) -> value == null ? "" : value);
        if (document.get("lastExitCode") == null || document.get("lastExitCode").equals("")) {
            document.put("lastExitCode", -1);
        }
        // rev：新文档从 1 起；既有文档在其 rev 上 +1（权威写使在途 CAS 全部失效）。
        long rev = 1L;
        Optional<Map<String, Object>> existing = documents.findById(COLLECTION, instance.id());
        if (existing.isPresent()) {
            rev = revision(existing.get()) + 1;
        }
        document.put(FIELD_REV, rev);
        return document;
    }

    private McpanelInstance toInstance(Map<String, Object> document) {
        Map<String, Object> copy = new LinkedHashMap<>(document);
        copy.remove(FIELD_REV);
        if (copy.containsKey("lastExitCode")) {
            Object code = copy.get("lastExitCode");
            if (code instanceof Number number && number.intValue() == -1) {
                copy.put("lastExitCode", null);
            }
        }
        // 旧 config.autoRestart 占位的迁移在聚合紧凑构造器内完成（读侧稳定、写侧落库）。
        return mapper.convertValue(copy, McpanelInstance.class);
    }

    private static long revision(Map<String, Object> document) {
        Object value = document.get(FIELD_REV);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            return 0L;
        }
    }
}
