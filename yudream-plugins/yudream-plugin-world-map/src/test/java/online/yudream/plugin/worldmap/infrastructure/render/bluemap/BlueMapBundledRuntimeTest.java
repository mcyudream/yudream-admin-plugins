package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueMapBundledRuntimeTest {

    @TempDir
    Path temp;

    @Test
    void materializesPinnedCliAndPlaceholderCompleteTemplate() throws Exception {
        BlueMapBundledRuntime.Materialized runtime = new BlueMapBundledRuntime().materialize(temp);

        assertTrue(Files.isRegularFile(runtime.cliJar()));
        assertTrue(Files.isRegularFile(runtime.configTemplate().resolve("core.conf")));
        assertTrue(Files.readString(runtime.configTemplate().resolve("core.conf")).contains("${data}"));
        assertTrue(Files.readString(runtime.configTemplate().resolve("maps/template.conf")).contains("${world}"));
        assertTrue(Files.readString(runtime.configTemplate().resolve("maps/template.conf"))
                .contains("ignore-missing-light-data: true"));
        assertTrue(Files.readString(runtime.configTemplate().resolve("storages/file.conf")).contains("${root}"));
    }
}
