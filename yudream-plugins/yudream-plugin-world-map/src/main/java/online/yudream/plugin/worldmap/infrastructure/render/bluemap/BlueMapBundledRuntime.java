package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Materializes immutable BlueMap resources from the plugin JAR into one render's work directory. */
public final class BlueMapBundledRuntime {

    private static final String ROOT = "bluemap/";
    private static final String CLI = ROOT + "cli/bluemap-5.16-cli.jar";
    private static final String CORE = ROOT + "config/core.conf";
    private static final String MAP_TEMPLATE = ROOT + "config/maps/template.conf";
    private static final String FILE_STORAGE = ROOT + "config/storages/file.conf";

    public Materialized materialize(Path workDir) throws IOException {
        Path root = workDir.toAbsolutePath().normalize().resolve("runtime/bluemap");
        Path cli = root.resolve("bluemap-5.16-cli.jar");
        Path config = root.resolve("config");
        copy(CLI, cli);
        copy(CORE, config.resolve("core.conf"));
        copy(MAP_TEMPLATE, config.resolve("maps/template.conf"));
        copy(FILE_STORAGE, config.resolve("storages/file.conf"));
        return new Materialized(cli, config);
    }

    private void copy(String resource, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (InputStream input = BlueMapBundledRuntime.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Bundled BlueMap resource is missing: " + resource);
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Materialized(Path cliJar, Path configTemplate) {
    }
}
