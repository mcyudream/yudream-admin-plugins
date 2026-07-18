package online.yudream.plugin.worldmap.application.service;

import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;
import online.yudream.plugin.worldmap.domain.aggregate.RenderTask;
import online.yudream.plugin.worldmap.domain.enumerate.MapState;
import online.yudream.plugin.worldmap.domain.enumerate.TaskState;
import online.yudream.plugin.worldmap.domain.repo.MapInstanceRepo;
import online.yudream.plugin.worldmap.domain.repo.RenderTaskRepo;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderOrchestratorTest {

    @Test
    void startupRecoveryFailsOwningMapWithStaleRunningTask() {
        InMemoryMapRepo maps = new InMemoryMapRepo();
        MapInstance map = new MapInstance("map-1", "Example", "overworld");
        map.markRendering();
        maps.save(map);

        InMemoryTaskRepo tasks = new InMemoryTaskRepo();
        RenderTask task = new RenderTask("task-1", map.getId());
        task.start(12);
        tasks.save(task);

        try (RenderOrchestrator ignored = new RenderOrchestrator(
                tasks,
                maps,
                new TileStorage(null),
                null,
                null,
                new WorldMapEventStream())) {
            assertEquals(TaskState.FAILED, task.getState());
            assertEquals(MapState.FAILED, map.getState());
        }
    }

    private static final class InMemoryMapRepo implements MapInstanceRepo {
        private final Map<String, MapInstance> values = new LinkedHashMap<>();

        @Override
        public MapInstance save(MapInstance map) {
            values.put(map.getId(), map);
            return map;
        }

        @Override
        public Optional<MapInstance> findById(String id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public List<MapInstance> findAll() {
            return new ArrayList<>(values.values());
        }

        @Override
        public void delete(String id) {
            values.remove(id);
        }
    }

    private static final class InMemoryTaskRepo implements RenderTaskRepo {
        private final Map<String, RenderTask> values = new LinkedHashMap<>();

        @Override
        public RenderTask save(RenderTask task) {
            values.put(task.getId(), task);
            return task;
        }

        @Override
        public Optional<RenderTask> findById(String id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public List<RenderTask> findByMapId(String mapId) {
            return values.values().stream().filter(task -> mapId.equals(task.getMapId())).toList();
        }

        @Override
        public List<RenderTask> findAll() {
            return new ArrayList<>(values.values());
        }

        @Override
        public void deleteByMapId(String mapId) {
            values.values().removeIf(task -> mapId.equals(task.getMapId()));
        }
    }
}
