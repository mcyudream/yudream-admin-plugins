package online.yudream.base.plugin.mcpanel.domain.repo;

import java.util.List;
import java.util.Optional;

/**
 * 端口分配仓储。文档 id 为确定性的 alloc:{nodeId}:{port}:{proto}：
 * 单文档 save 即分配（同 id 覆盖同一条目），释放即 delete；
 * 不假设跨文档事务——面板是每节点唯一分配方（M2 边界，见设计 §4）。
 */
public interface PortAllocationRepository {

    boolean allocate(String nodeId, int port, String proto, String instanceId);

    boolean release(String nodeId, int port, String proto);

    Optional<String> ownerOf(String nodeId, int port, String proto);

    List<Record> findByInstance(String instanceId);

    long countByNode(String nodeId);

    record Record(String nodeId, int port, String proto, String instanceId) {
    }
}
