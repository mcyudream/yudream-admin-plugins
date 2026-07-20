package online.yudream.plugin.worldmap.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.worldmap.domain.aggregate.RenderTask;
import online.yudream.plugin.worldmap.domain.enumerate.TaskState;
import online.yudream.plugin.worldmap.domain.enumerate.RenderPhase;
import online.yudream.plugin.worldmap.domain.repo.RenderTaskRepo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static online.yudream.plugin.worldmap.infrastructure.repository.DocumentMapInstanceRepo.intValue;
import static online.yudream.plugin.worldmap.infrastructure.repository.DocumentMapInstanceRepo.longValue;
import static online.yudream.plugin.worldmap.infrastructure.repository.DocumentMapInstanceRepo.stringValue;

/**
 * 基于插件文档存储的渲染任务仓储实现。
 */
public class DocumentRenderTaskRepo implements RenderTaskRepo {

    private static final String COLLECTION = "render_task";

    private final PluginDocumentStore store;

    public DocumentRenderTaskRepo(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public RenderTask save(RenderTask task) {
        store.save(COLLECTION, task.getId(), toDocument(task));
        return task;
    }

    @Override
    public Optional<RenderTask> findById(String id) {
        return store.findById(COLLECTION, id).map(this::toAggregate);
    }

    @Override
    public List<RenderTask> findByMapId(String mapId) {
        return store.findByField(COLLECTION, "mapId", mapId, 1, 200).stream().map(this::toAggregate).toList();
    }

    @Override
    public List<RenderTask> findAll() {
        long count = store.count(COLLECTION);
        return store.findAll(COLLECTION, 1, (int) Math.min(Math.max(count, 1), 500)).stream()
                .map(this::toAggregate)
                .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
                .toList();
    }

    @Override
    public void deleteByMapId(String mapId) {
        for (RenderTask task : findByMapId(mapId)) {
            store.delete(COLLECTION, task.getId());
        }
    }

    private Map<String, Object> toDocument(RenderTask task) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("mapId", task.getMapId());
        doc.put("state", task.getState().name());
        doc.put("phase", task.getPhase().name());
        doc.put("progressPercent", task.getProgressPercent());
        doc.put("totalTiles", task.getTotalTiles());
        doc.put("doneTiles", task.getDoneTiles());
        doc.put("message", task.getMessage());
        doc.put("createdAt", task.getCreatedAt());
        doc.put("startedAt", task.getStartedAt());
        doc.put("finishedAt", task.getFinishedAt());
        doc.put("error", task.getError());
        return doc;
    }

    private RenderTask toAggregate(Map<String, Object> doc) {
        RenderTask task = new RenderTask(
                stringValue(doc.get("_id"), stringValue(doc.get("id"), "")),
                stringValue(doc.get("mapId"), "")
        );
        task.setState(TaskState.valueOf(stringValue(doc.get("state"), "PENDING")));
        task.setPhase(phaseValue(doc.get("phase")));
        task.setProgressPercent(intValue(doc.get("progressPercent")));
        task.setTotalTiles(intValue(doc.get("totalTiles")));
        task.setDoneTiles(intValue(doc.get("doneTiles")));
        task.setMessage(stringValue(doc.get("message"), null));
        task.setCreatedAt(longValue(doc.get("createdAt")));
        task.setStartedAt(longValue(doc.get("startedAt")));
        task.setFinishedAt(longValue(doc.get("finishedAt")));
        task.setError(stringValue(doc.get("error"), null));
        return task;
    }

    private static RenderPhase phaseValue(Object value) {
        try {
            return RenderPhase.valueOf(stringValue(value, "IMPORT"));
        } catch (IllegalArgumentException ignored) {
            return RenderPhase.IMPORT;
        }
    }
}
