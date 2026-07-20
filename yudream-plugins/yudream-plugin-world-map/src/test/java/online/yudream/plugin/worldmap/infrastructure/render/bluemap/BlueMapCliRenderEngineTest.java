package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueMapCliRenderEngineTest {

    @TempDir
    Path temp;

    @Test
    void locatorRejectsAFileWithUnexpectedDigest() throws Exception {
        Path cli = Files.writeString(temp.resolve("bluemap-cli.jar"), "not a real cli");

        BlueMapCliLocator locator = new BlueMapCliLocator("00");

        assertThrows(IllegalStateException.class, () -> locator.verify(cli));
    }

    @Test
    void workerCommandUsesBoundedHeapPinnedCliAndIsolatedConfig() throws Exception {
        Path cli = Files.writeString(temp.resolve("bluemap-5.16-cli.jar"), "fixture");
        Path config = Files.createDirectories(temp.resolve("template"));
        Path work = Files.createDirectories(temp.resolve("work"));
        BlueMapCliRenderEngine engine = new BlueMapCliRenderEngine(
                Path.of("java21"), cli, config, 768, Duration.ofMinutes(45));

        List<String> command = engine.commandFor(work, "smoke-map", "1.21.4");

        assertEquals("java21", command.getFirst());
        assertTrue(command.contains("-Xmx768m"));
        assertTrue(command.contains(cli.toAbsolutePath().toString()));
        assertTrue(command.contains("-c"));
        assertTrue(command.contains(work.resolve("config").toAbsolutePath().toString()));
        assertTrue(command.contains("-r"));
        assertTrue(command.contains("-f"));
        assertTrue(command.contains("-m"));
        assertTrue(command.contains("smoke_map"));
        assertTrue(command.contains("-v"));
        assertTrue(command.contains("1.21.4"));
    }

    @Test
    void adapterRejectsStorageOutsideTheTaskDirectory() throws Exception {
        BlueMapRenderEngineAdapter adapter = new BlueMapRenderEngineAdapter();
        var method = BlueMapRenderEngineAdapter.class.getDeclaredMethod("resolveStorageParent", Path.class, Path.class);
        method.setAccessible(true);

        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(adapter, temp.resolve("work"), temp.resolve("outside")));

        assertTrue(error.getCause() instanceof java.io.IOException);
    }

    @Test
    void createsDynamicMapConfigWithTaskLocalWorldAndStorage() throws Exception {
        Path config = Files.createDirectories(temp.resolve("config"));
        Path maps = Files.createDirectories(config.resolve("maps"));
        Path storages = Files.createDirectories(config.resolve("storages"));
        Files.writeString(config.resolve("webserver.conf"), "webroot: C:/another-volume/web");
        Files.writeString(config.resolve("webapp.conf"), "webroot: C:/another-volume/web");
        Files.writeString(config.resolve("core.conf"), "accept-download: true\ndata: \"${data}\"\n");
        Files.writeString(maps.resolve("template.conf"), "world: \"${world}\"\ndimension: \"${dimension}\"\nname: \"${name}\"\nstorage: \"file\"\n");
        Files.writeString(storages.resolve("file.conf"), "storage-type: file\nroot: \"${root}\"\n");
        Path world = Files.createDirectories(temp.resolve("world"));
        Path client = clientJar("1.21.4");
        Path output = temp.resolve("output");
        Path resources = temp.resolve("resources/1.21.4");

        BlueMapCliRenderEngine.prepareTaskConfiguration(config, "map-1", world, client, "1.21.4", "nether", output, resources);

        String map = Files.readString(maps.resolve("map_1.conf"));
        assertTrue(map.contains("minecraft:the_nether"));
        assertTrue(map.contains("name: \"map_1\""));
        assertTrue(map.contains(world.toAbsolutePath().toString().replace('\\', '/')));
        assertTrue(Files.notExists(maps.resolve("template.conf")));
        assertTrue(Files.readString(storages.resolve("file.conf")).contains(output.toAbsolutePath().toString().replace('\\', '/')));
        assertTrue(Files.readString(config.resolve("core.conf")).contains("accept-download: true"));
        assertTrue(Files.readString(config.resolve("core.conf")).contains(resources.toAbsolutePath().toString().replace('\\', '/')));
        assertArrayEquals(Files.readAllBytes(client), Files.readAllBytes(resources.resolve("minecraft-client-1.21.4.jar")));
        assertTrue(Files.readString(config.resolve("webserver.conf")).contains("enabled: false"));
        assertTrue(Files.readString(config.resolve("webapp.conf")).contains("enabled: false"));
    }

    @Test
    void rejectsClientJarWithTheWrongMinecraftVersion() throws Exception {
        var error = assertThrows(java.io.IOException.class,
                () -> BlueMapCliRenderEngine.validateClientVersion(clientJar("1.21.1"), "1.21.4"));

        assertTrue(error.getMessage().contains("does not match"));
    }

    @Test
    void cancellingAnIdleWorkerIsSafe() {
        BlueMapCliRenderEngine engine = new BlueMapCliRenderEngine(
                Path.of("java21"), temp.resolve("cli.jar"), temp.resolve("template"), 768, Duration.ofMinutes(1));

        engine.cancel();
    }

    @Test
    void retainsOnlyTheActionableTailOfAWorkerFailureLog() throws Exception {
        Path log = temp.resolve("worker.log");
        Files.writeString(log, "prefix\n" + "x".repeat(4_100) + "\nBlueMap missing resources");

        String tail = BlueMapCliRenderEngine.logTail(log);

        assertTrue(tail.endsWith("BlueMap missing resources"));
        assertTrue(tail.length() <= 4_000);
    }

    private Path clientJar(String version) throws Exception {
        Path jar = temp.resolve("client-" + version + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write(("{\"id\":\"" + version + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }
}
