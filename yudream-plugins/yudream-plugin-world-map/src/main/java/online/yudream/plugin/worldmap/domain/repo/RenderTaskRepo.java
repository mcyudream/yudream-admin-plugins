package online.yudream.plugin.worldmap.domain.repo;

import online.yudream.plugin.worldmap.domain.aggregate.RenderTask;

import java.util.List;
import java.util.Optional;

public interface RenderTaskRepo {

    RenderTask save(RenderTask task);

    Optional<RenderTask> findById(String id);

    List<RenderTask> findByMapId(String mapId);

    List<RenderTask> findAll();

    /** 地图最近一次任务 */
    default Optional<RenderTask> findLatest(String mapId) {
        return findByMapId(mapId).stream()
                .max(java.util.Comparator.comparingLong(RenderTask::getCreatedAt));
    }

    void deleteByMapId(String mapId);
}
