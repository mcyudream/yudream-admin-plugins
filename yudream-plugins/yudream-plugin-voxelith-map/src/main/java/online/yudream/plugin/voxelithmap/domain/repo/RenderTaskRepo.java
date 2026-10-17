package online.yudream.plugin.voxelithmap.domain.repo;

import online.yudream.plugin.voxelithmap.domain.aggregate.RenderTask;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 渲染任务仓储端口。 */
public interface RenderTaskRepo {

    RenderTask save(RenderTask task);

    Optional<RenderTask> findById(String taskId);

    /** 按地图列出任务（新到旧）。 */
    List<RenderTask> findByMapId(String mapId);

    /** 全部任务（新到旧，供管理页任务列表）。 */
    List<RenderTask> findAll();

    void deleteByMapId(String mapId);

    /** 最近一条任务。 */
    default Optional<RenderTask> findLatest(String mapId) {
        return findByMapId(mapId).stream()
                .max(Comparator.comparing(RenderTask::createdAt));
    }
}
