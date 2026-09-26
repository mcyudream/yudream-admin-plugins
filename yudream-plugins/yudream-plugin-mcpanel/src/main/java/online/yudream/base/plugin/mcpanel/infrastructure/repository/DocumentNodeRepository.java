package online.yudream.base.plugin.mcpanel.infrastructure.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeQuery;
import online.yudream.base.plugin.mcpanel.domain.valobj.PageResult;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * 宿主文档存储仓储。
 *
 * <p>并发模型（同宿主内唯一写入口）：
 * <ul>
 *   <li>{@link #update} / {@link #withNodeLock} / {@link #markEnrolledOnce} 共用
 *       按 nodeId 固定 64 条纹的 {@link ReentrantLock}：admin 配置写与 runtime
 *       状态写走 {@link #update}；bootstrap 领取/注册与重签吊销包在
 *       {@link #withNodeLock} 内，同宿主对同一节点完全串行；</li>
 *   <li>{@link #markEnrolledOnce} 在条纹锁内读取仓内最新节点、仅合并注册字段
 *       （不信外部快照），再以宿主 {@code updateIfFieldAtMost}
 *       （单文档原子 CAS，条件 enrolledAtMs ≤ 0）兜底跨实例唯一注册；</li>
 *   <li>{@link #page}：先按 {@link #PAGE_SIZE} 200/页遍历全部分页直到取尽，
 *       再过滤/排序/切片——total 始终为真实总数，无窗口截断。</li>
 * </ul>
 */
public class DocumentNodeRepository implements McpanelNodeRepository {

    static final String COLLECTION = "nodes";
    private static final int PAGE_SIZE = 200;
    private static final int LOCK_STRIPES = 64;

    private final PluginDocumentStore documents;
    private final ObjectMapper mapper;
    private final ReentrantLock[] stripes;

    public DocumentNodeRepository(PluginDocumentStore documents, ObjectMapper mapper) {
        this.documents = documents;
        this.mapper = mapper;
        this.stripes = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    @Override
    public McpanelNode save(McpanelNode node) {
        documents.save(COLLECTION, node.id(), toDocument(node));
        return node;
    }

    @Override
    public Optional<McpanelNode> findById(String nodeId) {
        return documents.findById(COLLECTION, nodeId).map(this::toNode);
    }

    @Override
    public McpanelNode update(String nodeId, UnaryOperator<McpanelNode> merger) {
        ReentrantLock lock = stripe(nodeId);
        lock.lock();
        try {
            McpanelNode current = findById(nodeId).orElse(null);
            if (current == null) {
                return null;
            }
            McpanelNode merged = merger.apply(current);
            if (merged == null) {
                return null;
            }
            return save(merged);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> T withNodeLock(String nodeId, java.util.function.Supplier<T> action) {
        ReentrantLock lock = stripe(nodeId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public McpanelNode markEnrolledOnce(String nodeId, String agentVersion, String reportedHost,
                                        String reportedCertSha256, String pinToRegister, long nowMs) {
        ReentrantLock lock = stripe(nodeId);
        lock.lock();
        try {
            McpanelNode current = findById(nodeId).orElse(null);
            if (current == null || current.enrolled()) {
                return null;
            }
            // 只在仓内最新节点上合并注册字段；endpoint/enabled 等 admin 配置
            // 始终取当前值，绝不使用外部快照回写。
            McpanelNode merged = current.withReported(agentVersion, reportedHost, reportedCertSha256, nowMs);
            // enroll TOFU：仅当仓内 pin 为空白且调用方传入登记指纹时写入初始 pin，
            // 管理员已设指纹绝不覆盖（同一 CAS 写入，无中间态）。
            boolean hasBlankPin = current.pinSha256() == null || current.pinSha256().isBlank();
            if (pinToRegister != null && !pinToRegister.isBlank() && hasBlankPin) {
                merged = merged.withRegisteredPin(pinToRegister);
            }
            // 宿主单文档原子 CAS：条件 enrolledAtMs ≤ 0，同宿主锁内必然新鲜，
            // 跨实例竞态由该 CAS 兜底，仅一方成功。
            boolean claimed = documents.updateIfFieldAtMost(COLLECTION, nodeId, "enrolledAtMs", 0L,
                    toDocument(merged));
            return claimed ? merged : null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PageResult<McpanelNode> page(NodeQuery query) {
        int page = query.pageOrDefault();
        int size = query.sizeOrDefault();
        // 先遍历取全量（200/页直到取尽），再过滤/排序/切片；total 均为真实总数。
        List<McpanelNode> all = new ArrayList<>();
        int pageNo = 1;
        List<McpanelNode> batch;
        do {
            batch = documents.findAll(COLLECTION, pageNo, PAGE_SIZE).stream()
                    .map(this::toNode)
                    .toList();
            all.addAll(batch);
            pageNo++;
        } while (batch.size() == PAGE_SIZE);

        String status = query.status() == null ? null : query.status().trim().toLowerCase(Locale.ROOT);
        String keyword = query.keyword() == null ? "" : query.keyword().trim().toLowerCase(Locale.ROOT);
        List<McpanelNode> filtered = all.stream()
                .filter(node -> status == null || status.isBlank() || node.status().equalsIgnoreCase(status))
                .filter(node -> keyword.isEmpty() || matchesKeyword(node, keyword))
                .sorted(Comparator.comparingLong(McpanelNode::createdAtMs).reversed())
                .toList();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return PageResult.of(filtered.subList(from, to), filtered.size(), page, size);
    }

    @Override
    public void delete(String nodeId) {
        documents.delete(COLLECTION, nodeId);
    }

    private ReentrantLock stripe(String nodeId) {
        return stripes[(nodeId == null ? 0 : nodeId.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    private boolean matchesKeyword(McpanelNode node, String keyword) {
        return contains(node.name(), keyword) || contains(node.endpoint(), keyword)
                || contains(node.reportedHost(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * 聚合 → 文档。统一剔除顶层 null 值：宿主 Mongo 路径容忍 null（存 BSON null），
     * 但沙盒 Map.copyOf 路径历史版本对 null 直接 NPE；读取侧字段缺失与 null 等价
     * （Jackson 反序列化为 null），剔除安全。嵌套 map 内的 null 与宿主沙盒语义一致保留。
     */
    private Map<String, Object> toDocument(McpanelNode node) {
        Map<String, Object> raw = mapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value != null) {
                document.put(key, value);
            }
        });
        return document;
    }

    private McpanelNode toNode(java.util.Map<String, Object> document) {
        return mapper.convertValue(document, McpanelNode.class);
    }
}

