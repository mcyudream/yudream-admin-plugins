package online.yudream.base.plugin.mcpanel.infrastructure.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档存储端口分配仓储：确定性文档 id 承载原子性（save 覆盖同键即同一条目）。
 *
 * 并发模型（明确单 JVM 限制）：allocate 的 find+save 检查-写入由每键
 * （nodeId:port:proto）的 JVM 内监视器锁串行化，消除「并发 find 均 empty →
 * 双 save 静默互覆盖」的竞态。锁仅覆盖两次内存级文档操作，绝不跨 RPC 持有。
 * 多面板 JVM 同时对同一节点分配端口不受此锁保护——与设计 §4 一致，面板必须是
 * 每节点唯一分配方；本仓储的原子性边界 = 单 JVM。
 */
public class DocumentPortAllocationRepository implements PortAllocationRepository {

    static final String COLLECTION = "mcpanel_port_allocations";
    private static final int PAGE_SIZE = 200;

    private final PluginDocumentStore documents;
    private final ObjectMapper mapper;
    /** 每分配键一把监视器锁（惰性创建；单 JVM 面板进程内有效）。 */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    public DocumentPortAllocationRepository(PluginDocumentStore documents, ObjectMapper mapper) {
        this.documents = documents;
        this.mapper = mapper;
    }

    private static String docId(String nodeId, int port, String proto) {
        return "alloc:" + nodeId + ":" + port + ":" + proto;
    }

    private Object lockFor(String id) {
        return keyLocks.computeIfAbsent(id, key -> new Object());
    }

    @Override
    public boolean allocate(String nodeId, int port, String proto, String instanceId) {
        String id = docId(nodeId, port, proto);
        synchronized (lockFor(id)) {
            Optional<Map<String, Object>> existing = documents.findById(COLLECTION, id);
            if (existing.isPresent()) {
                return String.valueOf(existing.get().get("instanceId")).equals(instanceId);
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("nodeId", nodeId);
            document.put("port", port);
            document.put("proto", proto);
            document.put("instanceId", instanceId);
            document.put("createdAt", System.currentTimeMillis());
            documents.save(COLLECTION, id, document);
            return true;
        }
    }

    @Override
    public boolean release(String nodeId, int port, String proto) {
        String id = docId(nodeId, port, proto);
        synchronized (lockFor(id)) {
            documents.delete(COLLECTION, id);
        }
        return true;
    }

    @Override
    public Optional<String> ownerOf(String nodeId, int port, String proto) {
        return documents.findById(COLLECTION, docId(nodeId, port, proto))
                .map(document -> String.valueOf(document.get("instanceId")));
    }

    @Override
    public List<Record> findByInstance(String instanceId) {
        List<Record> records = new ArrayList<>();
        int page = 1;
        List<Record> batch;
        do {
            batch = documents.findByField(COLLECTION, "instanceId", instanceId, page, PAGE_SIZE)
                    .stream().map(this::toRecord).toList();
            records.addAll(batch);
            page++;
        } while (batch.size() == PAGE_SIZE);
        return records;
    }

    @Override
    public long countByNode(String nodeId) {
        // findByField 分页遍历后计数（count() 无过滤能力）。
        long count = 0;
        int page = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findByField(COLLECTION, "nodeId", nodeId, page, PAGE_SIZE);
            count += batch.size();
            page++;
        } while (batch.size() == PAGE_SIZE);
        return count;
    }

    private Record toRecord(Map<String, Object> document) {
        return new Record(String.valueOf(document.get("nodeId")),
                ((Number) document.get("port")).intValue(),
                String.valueOf(document.get("proto")),
                String.valueOf(document.get("instanceId")));
    }
}
