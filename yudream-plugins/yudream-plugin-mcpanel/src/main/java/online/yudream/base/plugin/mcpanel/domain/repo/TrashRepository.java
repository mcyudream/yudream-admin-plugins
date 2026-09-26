package online.yudream.base.plugin.mcpanel.domain.repo;

import online.yudream.base.plugin.mcpanel.domain.valobj.TrashRecord;

import java.util.List;
import java.util.Optional;

/** 回收站记录仓储。trashId = 原实例 id，全局唯一（实例 id 不复用）。 */
public interface TrashRepository {

    TrashRecord save(TrashRecord record);

    Optional<TrashRecord> findById(String trashId);

    /** 存储分页（keyword 对实例名/节点 id 做包含匹配，忽略大小写）。 */
    PageResult<TrashRecord> page(int page, int size, String keyword);

    boolean existsByNodeId(String nodeId);

    void delete(String trashId);

    record PageResult<TrashRecord>(List<TrashRecord> records, long total, int page, int size) {
    }
}
