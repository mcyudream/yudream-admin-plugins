package online.yudream.plugin.worldmap.application.service;

import online.yudream.plugin.worldmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Writes render output into an isolated namespace and publishes it atomically through the map pointer. */
public final class GenerationPublisher {

    private final TileStorage storage;
    private final ConcurrentHashMap<String, Set<String>> stagedKeys = new ConcurrentHashMap<>();

    public GenerationPublisher(TileStorage storage) {
        this.storage = storage;
    }

    public MapGeneration stage(String mapId) {
        String id = UUID.randomUUID().toString().replace("-", "");
        MapGeneration generation = new MapGeneration(mapId, id);
        stagedKeys.put(id, ConcurrentHashMap.newKeySet());
        return generation;
    }

    public void saveHires(MapGeneration generation, int tx, int tz, byte[] data) {
        String key = TileStorage.hiresKey(generation.mapId(), generation.id(), tx, tz);
        storage.put(key, data, "application/json");
        track(generation, key);
    }

    public void saveBlueMapHires(MapGeneration generation, int tx, int tz, byte[] data) {
        String key = TileStorage.blueMapHiresKey(generation.mapId(), generation.id(), tx, tz);
        storage.put(key, data, "application/octet-stream");
        track(generation, key);
    }

    public void saveLowres(MapGeneration generation, int lod, int tx, int tz, byte[] data) {
        String key = TileStorage.lowresKey(generation.mapId(), generation.id(), lod, tx, tz);
        storage.put(key, data, "image/png");
        track(generation, key);
    }

    public void saveAtlas(MapGeneration generation, byte[] data) {
        String key = TileStorage.atlasKey(generation.mapId(), generation.id());
        storage.put(key, data, "image/png");
        track(generation, key);
    }

    public void publish(MapInstance map, MapGeneration generation) {
        if (!map.getId().equals(generation.mapId())) {
            throw new IllegalArgumentException("Generation does not belong to the map");
        }
        if (storage.atlas(generation.mapId(), generation.id()).isEmpty()) {
            throw new IllegalStateException("Render generation is missing its atlas");
        }
        map.setActiveGenerationId(generation.id());
        stagedKeys.remove(generation.id());
    }

    public void discard(MapGeneration generation) {
        Set<String> keys = stagedKeys.remove(generation.id());
        if (keys != null) {
            keys.forEach(storage::delete);
        }
    }

    private void track(MapGeneration generation, String key) {
        stagedKeys.computeIfAbsent(generation.id(), ignored -> ConcurrentHashMap.newKeySet()).add(key);
    }
}
