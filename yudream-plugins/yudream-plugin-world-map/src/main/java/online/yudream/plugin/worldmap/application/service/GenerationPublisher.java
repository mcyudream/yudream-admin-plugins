package online.yudream.plugin.worldmap.application.service;

import online.yudream.plugin.worldmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.LinkedHashSet;

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

    public void saveBlueMapTextures(MapGeneration generation, byte[] data) {
        String key = TileStorage.blueMapTexturesKey(generation.mapId(), generation.id());
        storage.put(key, data, "application/json");
        track(generation, key);
    }

    public void saveBlueMapSettings(MapGeneration generation, byte[] data) {
        String key = TileStorage.blueMapSettingsKey(generation.mapId(), generation.id());
        storage.put(key, data, "application/json");
        track(generation, key);
    }

    public void saveBlueMapLowresIndex(MapGeneration generation, byte[] data) {
        String key = TileStorage.blueMapLowresIndexKey(generation.mapId(), generation.id());
        storage.put(key, data, "application/json");
        track(generation, key);
    }

    public void publish(MapInstance map, MapGeneration generation) {
        publish(map, generation, "YUDREAM");
    }

    public void publish(MapInstance map, MapGeneration generation, String renderer) {
        if (!map.getId().equals(generation.mapId())) {
            throw new IllegalArgumentException("Generation does not belong to the map");
        }
        boolean blueMap = "BLUEMAP".equals(renderer);
        if (blueMap ? storage.blueMapTextures(generation.mapId(), generation.id()).isEmpty()
                || storage.blueMapSettings(generation.mapId(), generation.id()).isEmpty()
                : storage.atlas(generation.mapId(), generation.id()).isEmpty()) {
            throw new IllegalStateException("Render generation is missing its renderer textures");
        }
        saveManifest(generation);
        map.setActiveGenerationId(generation.id());
        map.setActiveRenderer(blueMap ? "BLUEMAP" : "YUDREAM");
        map.addPublishedGenerationId(generation.id());
        stagedKeys.remove(generation.id());
    }

    public void discard(MapGeneration generation) {
        Set<String> keys = stagedKeys.remove(generation.id());
        if (keys != null) {
            keys.forEach(storage::delete);
        }
    }

    /** Deletes only files listed by a published generation's own manifest. */
    public void deletePublished(MapInstance map) {
        Set<String> generationIds = new LinkedHashSet<>(map.getPublishedGenerationIds());
        if (map.getActiveGenerationId() != null && !map.getActiveGenerationId().isBlank()) {
            generationIds.add(map.getActiveGenerationId());
        }
        for (String generationId : generationIds) {
            String prefix = "maps/" + map.getId() + "/generations/" + generationId + "/";
            storage.generationManifest(map.getId(), generationId).ifPresent(file -> {
                try (var input = file.inputStream()) {
                    String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    for (String key : text.lines().toList()) {
                        if (key.startsWith(prefix)) storage.delete(key);
                    }
                } catch (IOException ignored) {
                    // A partial cleanup must not prevent deleting the map record and its source files.
                }
            });
            storage.delete(TileStorage.generationManifestKey(map.getId(), generationId));
        }
    }

    private void saveManifest(MapGeneration generation) {
        Set<String> keys = stagedKeys.get(generation.id());
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("Render generation has no tracked assets");
        }
        String key = TileStorage.generationManifestKey(generation.mapId(), generation.id());
        storage.put(key, String.join("\n", keys).getBytes(StandardCharsets.UTF_8), "text/plain");
        track(generation, key);
    }

    private void track(MapGeneration generation, String key) {
        stagedKeys.computeIfAbsent(generation.id(), ignored -> ConcurrentHashMap.newKeySet()).add(key);
    }
}
