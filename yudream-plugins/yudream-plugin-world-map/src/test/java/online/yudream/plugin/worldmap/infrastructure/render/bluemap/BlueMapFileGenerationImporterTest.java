package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.plugin.worldmap.application.service.GenerationPublisher;
import online.yudream.plugin.worldmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueMapFileGenerationImporterTest {

    @TempDir
    Path output;

    @Test
    void importsGzipPrbmAndLowresPngFromBlueMapFileStorage() throws Exception {
        byte[] prbm = {1, 7, 0, 0, 0, 0, 0, 0};
        Path hires = Files.createDirectories(output.resolve("tiles/0/x-1/z2"));
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(hires.resolve("x-1z2.prbm.gz")))) {
            gzip.write(prbm);
        }
        Path lowres = Files.createDirectories(output.resolve("tiles/2/x0/z-1"));
        Files.write(lowres.resolve("x0z-1.png"), new byte[]{1, 2, 3});
        byte[] textures = "[{\"color\":[1,1,1,1],\"texture\":\"data:image/png;base64,AA==\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(output.resolve("textures.json"), textures);
        // BlueMap v5 serializes Vector2i values as JSON arrays, not { x, y } objects.
        byte[] settings = "{\"hires\":{\"tileSize\":[32,32],\"translate\":[2,2]},\"lowres\":{\"tileSize\":[500,500],\"lodCount\":3,\"lodFactor\":5}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(output.resolve("settings.json"), settings);

        InMemoryFileStore store = new InMemoryFileStore();
        GenerationPublisher publisher = new GenerationPublisher(new TileStorage(store));
        MapGeneration generation = publisher.stage("map-1");
        BlueMapImportSummary summary = new BlueMapFileGenerationImporter().importStorage(output, generation, publisher);

        assertEquals(1, summary.hiresTiles());
        assertEquals(1, summary.lowresTiles());
        assertArrayEquals(prbm, store.files.get(TileStorage.blueMapHiresKey("map-1", generation.id(), -1, 2)));
        assertArrayEquals(new byte[]{1, 2, 3}, store.files.get(TileStorage.lowresKey("map-1", generation.id(), 2, 0, -1)));
        assertArrayEquals(textures, store.files.get(TileStorage.blueMapTexturesKey("map-1", generation.id())));
        assertArrayEquals(settings, store.files.get(TileStorage.blueMapSettingsKey("map-1", generation.id())));
        String index = new String(store.files.get(TileStorage.blueMapLowresIndexKey("map-1", generation.id())),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("{\"levels\":{\"2\":[[-1,[[0,0]]]]}}", index);
    }

    @Test
    void rejectsOutputWithoutTheMaterialMetadataRequiredByPrbmGroups() throws Exception {
        Path hires = Files.createDirectories(output.resolve("tiles/0/x0/z0"));
        Files.write(hires.resolve("x0z0.prbm"), new byte[]{1, 7, 0, 0, 0, 0, 0, 0});
        Files.write(output.resolve("textures.json"), "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        GenerationPublisher publisher = new GenerationPublisher(new TileStorage(new InMemoryFileStore()));

        assertThrows(java.io.IOException.class,
                () -> new BlueMapFileGenerationImporter().importStorage(output, publisher.stage("map-1"), publisher));
    }

    @Test
    void rejectsInvalidBlueMapGridMetadataBeforePublishingAStagedGeneration() throws Exception {
        Path hires = Files.createDirectories(output.resolve("tiles/0/x0/z0"));
        Files.write(hires.resolve("x0z0.prbm"), new byte[]{1, 7, 0, 0, 0, 0, 0, 0});
        Files.write(output.resolve("textures.json"), "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(output.resolve("settings.json"), "{\"hires\":{},\"lowres\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        GenerationPublisher publisher = new GenerationPublisher(new TileStorage(new InMemoryFileStore()));

        assertThrows(java.io.IOException.class,
                () -> new BlueMapFileGenerationImporter().importStorage(output, publisher.stage("map-1"), publisher));
    }

    private static final class InMemoryFileStore implements PluginFileStore {
        private final Map<String, byte[]> files = new HashMap<>();
        @Override public String put(String key, InputStream input, long len, String contentType) {
            try { files.put(key, input.readAllBytes()); return key; } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        @Override public PluginStoredFile get(String key) {
            byte[] data = files.get(key);
            return data == null ? null : new PluginStoredFile(key, "application/octet-stream", (long) data.length, new ByteArrayInputStream(data));
        }
        @Override public void delete(String key) { files.remove(key); }
    }
}
