package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        List<String> command = engine.commandFor(work, "overworld", "1.21.4");

        assertEquals("java21", command.getFirst());
        assertTrue(command.contains("-Xmx768m"));
        assertTrue(command.contains(cli.toAbsolutePath().toString()));
        assertTrue(command.contains("-c"));
        assertTrue(command.contains(work.resolve("config").toAbsolutePath().toString()));
        assertTrue(command.contains("-f"));
        assertTrue(command.contains("-m"));
        assertTrue(command.contains("overworld"));
        assertTrue(command.contains("-v"));
        assertTrue(command.contains("1.21.4"));
    }

    @Test
    void adapterRejectsStorageOutsideTheTaskDirectory() throws Exception {
        BlueMapRenderEngineAdapter adapter = new BlueMapRenderEngineAdapter();
        var method = BlueMapRenderEngineAdapter.class.getDeclaredMethod("resolveStorageRoot", Path.class, Path.class);
        method.setAccessible(true);

        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(adapter, temp.resolve("work"), temp.resolve("outside")));

        assertTrue(error.getCause() instanceof java.io.IOException);
    }
}
