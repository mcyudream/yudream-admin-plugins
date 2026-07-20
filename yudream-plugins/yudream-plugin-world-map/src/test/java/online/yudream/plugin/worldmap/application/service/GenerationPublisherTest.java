package online.yudream.plugin.worldmap.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.plugin.worldmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationPublisherTest {

    @Test
    void incompleteGenerationCannotReplaceTheActiveGenerationAndIsDiscarded() {
        InMemoryFileStore store = new InMemoryFileStore();
        GenerationPublisher publisher = new GenerationPublisher(new TileStorage(store));
        MapInstance map = new MapInstance("map-1", "Example", "overworld");
        map.setActiveGenerationId("previous");
        MapGeneration staging = publisher.stage(map.getId());

        assertThrows(IllegalStateException.class, () -> publisher.publish(map, staging));
        assertEquals("previous", map.getActiveGenerationId());

        publisher.discard(staging);
        assertFalse(store.files.containsKey(TileStorage.atlasKey(map.getId(), staging.id())));
    }

    @Test
    void verifiedGenerationAtomicallyBecomesActiveAndKeepsThePreviousAssets() {
        InMemoryFileStore store = new InMemoryFileStore();
        GenerationPublisher publisher = new GenerationPublisher(new TileStorage(store));
        MapInstance map = new MapInstance("map-1", "Example", "overworld");
        map.setActiveGenerationId("previous");
        store.put(TileStorage.atlasKey(map.getId(), "previous"), new ByteArrayInputStream(new byte[]{1}), 1, "image/png");
        MapGeneration staging = publisher.stage(map.getId());
        publisher.saveAtlas(staging, new byte[]{2});

        publisher.publish(map, staging);

        assertEquals(staging.id(), map.getActiveGenerationId());
        assertTrue(store.files.containsKey(TileStorage.atlasKey(map.getId(), "previous")));
        assertTrue(store.files.containsKey(TileStorage.atlasKey(map.getId(), staging.id())));
    }

    @Test
    void blueMapGenerationRequiresTexturesMetadataInsteadOfAnAtlas() {
        InMemoryFileStore store = new InMemoryFileStore();
        GenerationPublisher publisher = new GenerationPublisher(new TileStorage(store));
        MapInstance map = new MapInstance("map-1", "Example", "overworld");
        MapGeneration staging = publisher.stage(map.getId());

        assertThrows(IllegalStateException.class, () -> publisher.publish(map, staging, "BLUEMAP"));
        publisher.saveBlueMapTextures(staging, "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> publisher.publish(map, staging, "BLUEMAP"));
        publisher.saveBlueMapSettings(staging, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        publisher.publish(map, staging, "BLUEMAP");

        assertEquals("BLUEMAP", map.getActiveRenderer());
        assertEquals(staging.id(), map.getActiveGenerationId());
    }

    @Test
    void publishedManifestDeletesOnlyKnownGenerationAssets() {
        InMemoryFileStore store = new InMemoryFileStore();
        GenerationPublisher publisher = new GenerationPublisher(new TileStorage(store));
        MapInstance map = new MapInstance("map-1", "Example", "overworld");
        MapGeneration generation = publisher.stage(map.getId());
        publisher.saveAtlas(generation, new byte[]{2});
        publisher.saveLowres(generation, 0, 1, 2, new byte[]{3});
        publisher.publish(map, generation);
        store.put("maps/map-1/generations/other/untracked.bin", new ByteArrayInputStream(new byte[]{4}), 1, "application/octet-stream");

        publisher.deletePublished(map);

        assertFalse(store.files.containsKey(TileStorage.atlasKey("map-1", generation.id())));
        assertFalse(store.files.containsKey(TileStorage.lowresKey("map-1", generation.id(), 0, 1, 2)));
        assertFalse(store.files.containsKey(TileStorage.generationManifestKey("map-1", generation.id())));
        assertTrue(store.files.containsKey("maps/map-1/generations/other/untracked.bin"));
    }

    private static final class InMemoryFileStore implements PluginFileStore {
        private final Map<String, byte[]> files = new HashMap<>();

        @Override
        public String put(String key, InputStream input, long len, String contentType) {
            try {
                files.put(key, input.readAllBytes());
                return key;
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public PluginStoredFile get(String key) {
            byte[] data = files.get(key);
            return data == null ? null : new PluginStoredFile(key, "application/octet-stream", (long) data.length,
                    new ByteArrayInputStream(data));
        }

        @Override
        public void delete(String key) {
            files.remove(key);
        }
    }
}
