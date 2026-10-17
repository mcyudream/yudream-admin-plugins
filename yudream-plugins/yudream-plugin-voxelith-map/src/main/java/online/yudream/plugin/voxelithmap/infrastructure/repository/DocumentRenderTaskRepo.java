package online.yudream.plugin.voxelithmap.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.voxelithmap.domain.aggregate.RenderTask;
import online.yudream.plugin.voxelithmap.domain.enumerate.RenderPhase;
import online.yudream.plugin.voxelithmap.domain.enumerate.TaskKind;
import online.yudream.plugin.voxelithmap.domain.enumerate.TaskState;
import online.yudream.plugin.voxelithmap.domain.repo.RenderTaskRepo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 渲染任务仓储（{@code render_task} 集合）。 */
public final class DocumentRenderTaskRepo implements RenderTaskRepo {

    public static final String COLLECTION = "render_task";

    /** 宿主 findByField 单页上限，超过则说明该地图任务堆太多了，列表只需最近的部分。 */
    private static final int PAGE_SIZE = 200;
    /** 管理页任务列表最多展示的条数。 */
    private static final int MAX_TASKS = 500;

    private final PluginDocumentStore store;

    public DocumentRenderTaskRepo(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public RenderTask save(RenderTask task) {
        store.save(COLLECTION, task.id(), toDocument(task));
        return task;
    }

    @Override
    public Optional<RenderTask> findById(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return store.findById(COLLECTION, taskId).map(this::toAggregate);
    }

    @Override
    public List<RenderTask> findByMapId(String mapId) {
        List<RenderTask> tasks = new ArrayList<>();
        for (Map<String, Object> document : store.findByField(COLLECTION, "mapId", mapId, 1, PAGE_SIZE)) {
            tasks.add(toAggregate(document));
        }
        tasks.sort(Comparator.comparing(RenderTask::createdAt).reversed());
        return List.copyOf(tasks);
    }

    @Override
    public List<RenderTask> findAll() {
        List<RenderTask> tasks = new ArrayList<>();
        long count = store.count(COLLECTION);
        int pages = (int) Math.max(1, Math.ceil(count / (double) PAGE_SIZE));
        for (int page = 1; page <= pages && tasks.size() < MAX_TASKS; page++) {
            for (Map<String, Object> document : store.findAll(COLLECTION, page, PAGE_SIZE)) {
                tasks.add(toAggregate(document));
            }
        }
        tasks.sort(Comparator.comparing(RenderTask::createdAt).reversed());
        return tasks.size() > MAX_TASKS ? List.copyOf(tasks.subList(0, MAX_TASKS)) : List.copyOf(tasks);
    }

    @Override
    public void deleteByMapId(String mapId) {
        for (Map<String, Object> document : store.findByField(COLLECTION, "mapId", mapId, 1, PAGE_SIZE)) {
            String id = stringValue(document.get("_id"), stringValue(document.get("id"), ""));
            if (!id.isBlank()) {
                store.delete(COLLECTION, id);
            }
        }
    }

    private Map<String, Object> toDocument(RenderTask task) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("kind", task.kind().name());
        document.put("subject", task.subject());
        document.put("mapId", task.mapId());
        document.put("generationId", task.generationId());
        document.put("state", task.state().name());
        document.put("phase", task.phase().name());
        document.put("percent", task.percent());
        document.put("completedTiles", task.completedTiles());
        document.put("totalTiles", task.totalTiles());
        document.put("message", task.message());
        document.put("logTail", task.logTail());
        document.put("diagnostics", task.diagnostics());
        document.put("error", task.error());
        document.put("createdAt", task.createdAt().toString());
        document.put("startedAt", task.startedAt() == null ? null : task.startedAt().toString());
        document.put("finishedAt", task.finishedAt() == null ? null : task.finishedAt().toString());
        return document;
    }

    private RenderTask toAggregate(Map<String, Object> document) {
        return RenderTask.restore(
                stringValue(document.get("_id"), stringValue(document.get("id"), "")),
                kindOf(document.get("kind")),
                stringValue(document.get("subject"), ""),
                stringValue(document.get("mapId"), ""),
                stringValue(document.get("generationId"), ""),
                stateOf(document.get("state")),
                phaseOf(document.get("phase")),
                intValue(document.get("percent"), 0),
                intValue(document.get("completedTiles"), 0),
                intValue(document.get("totalTiles"), 0),
                nullableString(document.get("message")),
                nullableString(document.get("logTail")),
                stringList(document.get("diagnostics")),
                nullableString(document.get("error")),
                instantOf(document.get("createdAt")),
                instantOrNull(document.get("startedAt")),
                instantOrNull(document.get("finishedAt")));
    }

    /** 旧记录没有 kind 字段：按渲染任务还原（本字段引入前只有渲染任务）。 */
    private static TaskKind kindOf(Object raw) {
        String value = stringValue(raw, "");
        if (value.isBlank()) {
            return TaskKind.RENDER;
        }
        try {
            return TaskKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TaskKind.RENDER;
        }
    }

    private static TaskState stateOf(Object raw) {
        try {
            String value = stringValue(raw, "");
            return value.isBlank() ? TaskState.PENDING : TaskState.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TaskState.PENDING;
        }
    }

    private static RenderPhase phaseOf(Object raw) {
        try {
            String value = stringValue(raw, "");
            return value.isBlank() ? RenderPhase.QUEUED : RenderPhase.valueOf(value);
        } catch (IllegalArgumentException e) {
            return RenderPhase.QUEUED;
        }
    }

    private static String stringValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw);
        return value.isBlank() ? fallback : value;
    }

    /** 文档里的字符串列表（老记录没有这个字段，返回空表而不是 null）。 */
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String value = item == null ? "" : String.valueOf(item);
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private static String nullableString(Object raw) {
        String value = stringValue(raw, "");
        return value.isBlank() ? null : value;
    }

    private static int intValue(Object raw, int fallback) {
        return raw instanceof Number number ? number.intValue() : fallback;
    }

    private static Instant instantOf(Object raw) {
        Instant value = instantOrNull(raw);
        return value == null ? Instant.now() : value;
    }

    private static Instant instantOrNull(Object raw) {
        String value = stringValue(raw, "");
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
