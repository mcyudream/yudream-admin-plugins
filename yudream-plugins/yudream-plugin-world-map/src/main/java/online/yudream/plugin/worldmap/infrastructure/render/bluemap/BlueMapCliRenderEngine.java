package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Isolated BlueMap v5.16 CLI launcher. The caller supplies a preconfigured template whose
 * configuration is copied into the task work directory, so no render shares BlueMap state.
 */
public final class BlueMapCliRenderEngine {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path javaExecutable;
    private final Path cliJar;
    private final Path configTemplate;
    private final int maxHeapMiB;
    private final Duration timeout;

    public BlueMapCliRenderEngine(Path javaExecutable, Path cliJar, Path configTemplate,
                                  int maxHeapMiB, Duration timeout) {
        if (javaExecutable == null || cliJar == null || configTemplate == null) {
            throw new IllegalArgumentException("BlueMap worker paths are required");
        }
        if (maxHeapMiB < 256) {
            throw new IllegalArgumentException("BlueMap worker heap must be at least 256 MiB");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("BlueMap worker timeout must be positive");
        }
        this.javaExecutable = javaExecutable;
        this.cliJar = cliJar;
        this.configTemplate = configTemplate;
        this.maxHeapMiB = maxHeapMiB;
        this.timeout = timeout;
    }

    /** Runs one forced render and returns the task-local log file. */
    public Path render(Path workDir, String mapId, String minecraftVersion, Path worldDir, Path clientJar,
                       String dimension, Path storageRoot) throws IOException {
        String blueMapMapId = blueMapMapId(mapId);
        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedWorkDir);
        Path configDir = normalizedWorkDir.resolve("config");
        copyConfiguration(configTemplate, configDir);
        prepareTaskConfiguration(configDir, blueMapMapId, worldDir, clientJar, minecraftVersion, dimension, storageRoot);
        Path logFile = normalizedWorkDir.resolve("bluemap.log");
        Process process = new ProcessBuilder(commandFor(normalizedWorkDir, blueMapMapId, minecraftVersion))
                .directory(normalizedWorkDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new IOException("BlueMap CLI timed out after " + timeout);
            }
        } catch (InterruptedException exception) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new IOException("BlueMap CLI render interrupted", exception);
        }
        if (process.exitValue() != 0) {
            throw new IOException("BlueMap CLI failed with exit code " + process.exitValue() + "; see " + logFile);
        }
        return logFile;
    }

    /**
     * Turns the administrator-maintained template into a map configuration scoped to one render.
     * The file storage appends the map id to its root, so {@code root} deliberately names the
     * parent output directory rather than the final map directory.
     */
    static void prepareTaskConfiguration(Path configDir, String mapId, Path worldDir, Path clientJar,
                                         String minecraftVersion, String dimension, Path storageRoot) throws IOException {
        String blueMapMapId = blueMapMapId(mapId);
        if (worldDir == null || !Files.isDirectory(worldDir) || clientJar == null || !Files.isRegularFile(clientJar)
                || minecraftVersion == null || minecraftVersion.isBlank() || storageRoot == null) {
            throw new IOException("BlueMap task world, client JAR, version and storage root are required");
        }
        validateClientVersion(clientJar, minecraftVersion.trim());
        Path template = configDir.resolve("maps/template.conf");
        Path storage = configDir.resolve("storages/file.conf");
        Path core = configDir.resolve("core.conf");
        if (!Files.isRegularFile(template) || !Files.isRegularFile(storage) || !Files.isRegularFile(core)) {
            throw new IOException("BlueMap template must contain core.conf, maps/template.conf and storages/file.conf");
        }
        String templateText = Files.readString(template);
        String storageText = Files.readString(storage);
        String coreText = Files.readString(core);
        requireToken(templateText, "${world}", template);
        requireToken(templateText, "${dimension}", template);
        requireToken(storageText, "${root}", storage);
        requireToken(coreText, "${data}", core);
        String mapText = replace(templateText, "${world}", configPath(worldDir));
        mapText = replace(mapText, "${dimension}", dimensionKey(dimension));
        mapText = replace(mapText, "${name}", blueMapMapId);
        Files.writeString(configDir.resolve("maps").resolve(blueMapMapId + ".conf"), mapText,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.delete(template);
        Files.writeString(storage, replace(storageText, "${root}", configPath(storageRoot)),
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        Path data = configDir.resolve("data");
        Files.createDirectories(data);
        Files.copy(clientJar, data.resolve("minecraft-client-" + minecraftVersion.trim() + ".jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        coreText = replace(coreText, "${data}", configPath(data));
        // Do not let a task-local worker contact Mojang or mutate shared resource state.
        coreText = coreText.replaceAll("(?m)^\\s*accept-download\\s*:\\s*.*$", "accept-download: false");
        if (!coreText.matches("(?s).*?\\baccept-download\\s*:.*")) {
            coreText += System.lineSeparator() + "accept-download: false" + System.lineSeparator();
        }
        Files.writeString(core, coreText, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    static void validateClientVersion(Path clientJar, String expectedVersion) throws IOException {
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            var entry = zip.getEntry("version.json");
            if (entry == null) throw new IOException("Minecraft client JAR does not contain version.json");
            try (var input = zip.getInputStream(entry)) {
                JsonNode root = JSON.readTree(input);
                String actualVersion = root == null ? null : root.path("id").asText(null);
                if (!expectedVersion.equals(actualVersion)) {
                    throw new IOException("Minecraft client JAR version " + actualVersion
                            + " does not match BlueMap worker version " + expectedVersion);
                }
            }
        }
    }

    /** Mirrors BlueMap v5.16's config filename-to-map-id normalization. */
    static String blueMapMapId(String mapId) throws IOException {
        if (mapId == null || mapId.isBlank() || !mapId.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("BlueMap map id contains unsupported characters");
        }
        return mapId.replaceAll("\\W", "_");
    }

    private static void requireToken(String text, String token, Path file) throws IOException {
        if (!text.contains(token)) throw new IOException("BlueMap template is missing " + token + " in " + file);
    }

    private static String replace(String text, String token, String value) {
        return text.replace(token, value.replace("\\", "/"));
    }

    private static String configPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String dimensionKey(String dimension) throws IOException {
        return switch (dimension) {
            case "overworld", "minecraft:overworld" -> "minecraft:overworld";
            case "nether", "minecraft:the_nether" -> "minecraft:the_nether";
            case "the_end", "end", "minecraft:the_end" -> "minecraft:the_end";
            default -> throw new IOException("Unsupported BlueMap dimension: " + dimension);
        };
    }

    List<String> commandFor(Path workDir, String mapId, String minecraftVersion) {
        final String blueMapMapId;
        try {
            blueMapMapId = blueMapMapId(mapId);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        Path configDir = workDir.toAbsolutePath().normalize().resolve("config");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Xmx" + maxHeapMiB + "m");
        command.add("-jar");
        command.add(cliJar.toAbsolutePath().normalize().toString());
        command.add("-c");
        command.add(configDir.toString());
        command.add("-f");
        command.add("-m");
        command.add(blueMapMapId);
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            command.add("-v");
            command.add(minecraftVersion);
        }
        return List.copyOf(command);
    }

    private static void copyConfiguration(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("BlueMap configuration template does not exist: " + source);
        }
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
