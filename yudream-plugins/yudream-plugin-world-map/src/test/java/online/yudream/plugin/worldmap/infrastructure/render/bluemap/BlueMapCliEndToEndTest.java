package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.plugin.worldmap.application.service.GenerationPublisher;
import online.yudream.plugin.worldmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;
import online.yudream.plugin.worldmap.infrastructure.storage.TileStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional smoke test for the real BlueMap v5.16 process and the plugin-owned generation boundary.
 * It is intentionally opt-in because it needs a local Minecraft world, client JAR and BlueMap data.
 */
class BlueMapCliEndToEndTest {

    private static final String PROPERTY_PREFIX = "yudream.world-map.e2e.";

    @TempDir
    Path temp;

    @Test
    void rendersImportsAndPublishesARealBlueMapGeneration() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PROPERTY_PREFIX + "enabled"),
                "Set -D" + PROPERTY_PREFIX + "enabled=true to run the real BlueMap CLI smoke test");

        Path java = requiredFile("java-path");
        Path cli = new BlueMapCliLocator().verify(requiredFile("cli-path"));
        Path world = requiredDirectory("world-dir");
        Path client = requiredFile("client-jar");
        Path resourceData = requiredDirectory("resource-data-root");
        String version = System.getProperty(PROPERTY_PREFIX + "minecraft-version", "1.21.4");
        Path template = createTemplate();
        Path work = temp.resolve("work");
        Path output = work.resolve("output");

        BlueMapCliRenderEngine worker = new BlueMapCliRenderEngine(java, cli, template, 1024, Duration.ofMinutes(5));
        Path log = worker.render(work, "cli-e2e", version, world, client, "overworld", output, resourceData);

        assertTrue(Files.isRegularFile(log));
        Path generatedStorage = output.resolve("cli_e2e");
        assertTrue(Files.isRegularFile(generatedStorage.resolve("settings.json")));
        assertTrue(Files.isRegularFile(generatedStorage.resolve("textures.json"))
                || Files.isRegularFile(generatedStorage.resolve("textures.json.gz")));

        InMemoryFileStore files = new InMemoryFileStore();
        GenerationPublisher publisher = new GenerationPublisher(new TileStorage(files));
        MapGeneration generation = publisher.stage("cli-e2e");
        BlueMapImportSummary imported = new BlueMapFileGenerationImporter().importStorage(generatedStorage, generation, publisher);

        assertTrue(imported.hiresTiles() > 0, "the real CLI must emit PRBM hires terrain");
        assertTrue(imported.lowresTiles() > 0, "the real CLI must emit overview terrain");
        MapInstance map = new MapInstance("cli-e2e", "CLI E2E", "overworld");
        publisher.publish(map, generation, "BLUEMAP");

        assertEquals(generation.id(), map.getActiveGenerationId());
        assertEquals("BLUEMAP", map.getActiveRenderer());
        assertTrue(map.getPublishedGenerationIds().contains(generation.id()));
        assertTrue(files.files.containsKey(TileStorage.blueMapSettingsKey(map.getId(), generation.id())));
        assertTrue(files.files.containsKey(TileStorage.blueMapTexturesKey(map.getId(), generation.id())));
        assertTrue(files.files.containsKey(TileStorage.blueMapLowresIndexKey(map.getId(), generation.id())));
        assertTrue(files.files.keySet().stream().anyMatch(key -> key.endsWith(".prbm")));
    }

    private Path createTemplate() throws IOException {
        Path config = Files.createDirectories(temp.resolve("template"));
        Files.createDirectories(config.resolve("maps"));
        Files.createDirectories(config.resolve("storages"));
        Files.writeString(config.resolve("core.conf"), "accept-download: true\ndata: \"${data}\"\n"
                + "render-thread-count: 1\nmetrics: false\nscan-for-mod-resources: false\n");
        Files.writeString(config.resolve("maps/template.conf"), "world: \"${world}\"\n"
                + "dimension: \"${dimension}\"\nname: \"${name}\"\nstorage: \"file\"\nrender-edges: false\n");
        Files.writeString(config.resolve("storages/file.conf"), "storage-type: file\nroot: \"${root}\"\ncompression: gzip\n");
        return config;
    }

    private static Path requiredFile(String name) {
        Path path = requiredPath(name);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Expected readable file for -D" + PROPERTY_PREFIX + name);
        return path;
    }

    private static Path requiredDirectory(String name) {
        Path path = requiredPath(name);
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Expected readable directory for -D" + PROPERTY_PREFIX + name);
        return path;
    }

    private static Path requiredPath(String name) {
        String value = System.getProperty(PROPERTY_PREFIX + name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing -D" + PROPERTY_PREFIX + name);
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static final class InMemoryFileStore implements PluginFileStore {
        private final Map<String, byte[]> files = new HashMap<>();

        @Override
        public String put(String key, InputStream input, long len, String contentType) {
            try {
                files.put(key, input.readAllBytes());
                return key;
            } catch (IOException exception) {
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
