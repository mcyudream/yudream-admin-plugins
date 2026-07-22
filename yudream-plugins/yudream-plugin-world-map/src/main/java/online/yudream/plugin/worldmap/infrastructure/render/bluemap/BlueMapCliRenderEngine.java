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
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Isolated BlueMap v5.16 CLI launcher. The caller supplies a preconfigured template whose
 * configuration is copied into the task work directory, so no render shares BlueMap state.
 */
public final class BlueMapCliRenderEngine {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FAILURE_LOG_CHARS = 4_000;
    private static final long PROGRESS_POLL_MILLIS = 500;
    private static final Pattern RENDER_PROGRESS = Pattern.compile(
            "updating map '.+?':\\s*([0-9]+(?:\\.[0-9]+)?)%", Pattern.CASE_INSENSITIVE);

    private final Path javaExecutable;
    private final Path cliJar;
    private final Path configTemplate;
    private final int maxHeapMiB;
    private final Duration timeout;
    private final int renderThreadCount;
    private volatile Process activeProcess;

    public BlueMapCliRenderEngine(Path javaExecutable, Path cliJar, Path configTemplate,
                                  int maxHeapMiB, Duration timeout) {
        this(javaExecutable, cliJar, configTemplate, maxHeapMiB, timeout, 3);
    }

    public BlueMapCliRenderEngine(Path javaExecutable, Path cliJar, Path configTemplate,
                                  int maxHeapMiB, Duration timeout, int renderThreadCount) {
        if (javaExecutable == null || cliJar == null || configTemplate == null) {
            throw new IllegalArgumentException("BlueMap worker paths are required");
        }
        if (maxHeapMiB < 256) {
            throw new IllegalArgumentException("BlueMap worker heap must be at least 256 MiB");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("BlueMap worker timeout must be positive");
        }
        if (renderThreadCount < 1) {
            throw new IllegalArgumentException("BlueMap worker render thread count must be positive");
        }
        this.javaExecutable = javaExecutable;
        this.cliJar = cliJar;
        this.configTemplate = configTemplate;
        this.maxHeapMiB = maxHeapMiB;
        this.timeout = timeout;
        this.renderThreadCount = renderThreadCount;
    }

    /** Runs one forced render and returns the task-local log file. */
    public Path render(Path workDir, String mapId, String minecraftVersion, Path worldDir, Path clientJar,
                       String dimension, Path storageRoot, Path resourceDataRoot) throws IOException {
        return render(workDir, mapId, minecraftVersion, worldDir, clientJar, dimension, storageRoot, resourceDataRoot, null);
    }

    /** Runs one forced render and reports the latest BlueMap CLI percentage while the process is alive. */
    public Path render(Path workDir, String mapId, String minecraftVersion, Path worldDir, Path clientJar,
                       String dimension, Path storageRoot, Path resourceDataRoot, DoubleConsumer progressConsumer) throws IOException {
        String blueMapMapId = blueMapMapId(mapId);
        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedWorkDir);
        Path configDir = normalizedWorkDir.resolve("config");
        copyConfiguration(configTemplate, configDir);
        prepareTaskConfiguration(configDir, blueMapMapId, worldDir, clientJar, minecraftVersion, dimension,
                storageRoot, resourceDataRoot, renderThreadCount);
        Path logFile = normalizedWorkDir.resolve("bluemap.log");
        Process process = new ProcessBuilder(commandFor(normalizedWorkDir, blueMapMapId, minecraftVersion))
                .directory(normalizedWorkDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        activeProcess = process;
        try {
            awaitRender(process, logFile, progressConsumer);
        } catch (InterruptedException exception) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw new IOException("BlueMap CLI render interrupted", exception);
        } finally {
            activeProcess = null;
        }
        if (process.exitValue() != 0) {
            throw new IOException("BlueMap CLI failed with exit code " + process.exitValue() + ": " + logTail(logFile));
        }
        return logFile;
    }

    private void awaitRender(Process process, Path logFile, DoubleConsumer progressConsumer) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        double lastReported = -1;
        while (true) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                terminate(process);
                throw new IOException("BlueMap CLI timed out after " + timeout);
            }
            long waitMillis = Math.max(1, Math.min(PROGRESS_POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                reportProgress(logFile, progressConsumer, lastReported);
                return;
            }
            double reported = reportProgress(logFile, progressConsumer, lastReported);
            if (reported >= 0) lastReported = reported;
        }
    }

    private static double reportProgress(Path logFile, DoubleConsumer progressConsumer, double lastReported) {
        if (progressConsumer == null || !Files.isRegularFile(logFile)) return lastReported;
        try {
            double progress = progressPercent(Files.readString(logFile));
            if (progress > lastReported) {
                progressConsumer.accept(progress);
                return progress;
            }
        } catch (IOException ignored) {
            // The next polling pass can read a log file that is still being created by the process.
        }
        return lastReported;
    }

    static double progressPercent(String log) {
        if (log == null || log.isBlank()) return -1;
        Matcher matcher = RENDER_PROGRESS.matcher(log);
        double latest = -1;
        while (matcher.find()) {
            latest = Math.min(100, Double.parseDouble(matcher.group(1)));
        }
        return latest;
    }

    /** Stops the isolated CLI immediately when its enclosing render task is cancelled. */
    public void cancel() {
        Process process = activeProcess;
        if (process != null && process.isAlive()) terminate(process);
    }

    /**
     * Turns the administrator-maintained template into a map configuration scoped to one render.
     * The file storage appends the map id to its root, so {@code root} deliberately names the
     * parent output directory rather than the final map directory.
     */
    static void prepareTaskConfiguration(Path configDir, String mapId, Path worldDir, Path clientJar,
                                         String minecraftVersion, String dimension, Path storageRoot,
                                         Path resourceDataRoot) throws IOException {
        prepareTaskConfiguration(configDir, mapId, worldDir, clientJar, minecraftVersion, dimension, storageRoot,
                resourceDataRoot, 3);
    }

    static void prepareTaskConfiguration(Path configDir, String mapId, Path worldDir, Path clientJar,
                                         String minecraftVersion, String dimension, Path storageRoot,
                                         Path resourceDataRoot, int renderThreadCount) throws IOException {
        String blueMapMapId = blueMapMapId(mapId);
        if (worldDir == null || !Files.isDirectory(worldDir) || clientJar == null || !Files.isRegularFile(clientJar)
                || minecraftVersion == null || minecraftVersion.isBlank() || storageRoot == null || resourceDataRoot == null) {
            throw new IOException("BlueMap task world, client JAR, version and storage root are required");
        }
        if (renderThreadCount < 1) {
            throw new IOException("BlueMap render thread count must be positive");
        }
        validateClientVersion(clientJar, minecraftVersion.trim());
        // The worker only renders map assets. BlueMap generates web configs again when they are
        // absent, so replace template-provided configs with harmless task-local disabled ones.
        disableWebServices(configDir);
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
        // Uploaded worlds commonly contain chunks without persisted light arrays. BlueMap otherwise
        // omits those chunks, leaving a sparse 3D map even though the Anvil region contains terrain.
        mapText = withIgnoreMissingLightData(mapText);
        Files.writeString(configDir.resolve("maps").resolve(blueMapMapId + ".conf"), mapText,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.delete(template);
        Files.writeString(storage, replace(storageText, "${root}", configPath(storageRoot)),
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        Path data = resourceDataRoot.toAbsolutePath().normalize();
        Files.createDirectories(data);
        Files.copy(clientJar, data.resolve("minecraft-client-" + minecraftVersion.trim() + ".jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        coreText = replace(coreText, "${data}", configPath(data));
        // BlueMap cannot prepare its renderer resources in a fresh task directory unless this is
        // accepted. The worker has an isolated data directory, so it cannot mutate host state.
        coreText = coreText.replaceAll("(?m)^\\s*accept-download\\s*:\\s*.*$", "accept-download: true");
        if (!coreText.matches("(?s).*?\\baccept-download\\s*:.*")) {
            coreText += System.lineSeparator() + "accept-download: true" + System.lineSeparator();
        }
        coreText = withRenderThreadCount(coreText, renderThreadCount);
        Files.writeString(core, coreText, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String withRenderThreadCount(String coreText, int renderThreadCount) {
        String setting = "render-thread-count: " + renderThreadCount;
        if (coreText.matches("(?s).*?\\brender-thread-count\\s*:.*")) {
            return coreText.replaceAll("(?m)^\\s*render-thread-count\\s*:\\s*.*$", setting);
        }
        return coreText + (coreText.endsWith(System.lineSeparator()) ? "" : System.lineSeparator())
                + setting + System.lineSeparator();
    }

    private static String withIgnoreMissingLightData(String mapText) {
        String setting = "ignore-missing-light-data: true";
        if (mapText.matches("(?s).*?\\bignore-missing-light-data\\s*:.*")) {
            return mapText.replaceAll("(?m)^\\s*ignore-missing-light-data\\s*:\\s*.*$", setting);
        }
        return mapText + (mapText.endsWith(System.lineSeparator()) ? "" : System.lineSeparator())
                + setting + System.lineSeparator();
    }

    private static void disableWebServices(Path configDir) throws IOException {
        String disabled = "enabled: false" + System.lineSeparator() + "webroot: \"web\"" + System.lineSeparator();
        Files.writeString(configDir.resolve("webserver.conf"), disabled,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(configDir.resolve("webapp.conf"), disabled,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    static void validateClientVersion(Path clientJar, String expectedVersion) throws IOException {
        resolveMinecraftVersion(clientJar, expectedVersion);
    }

    /** Uses the client JAR's declared version unless an administrator explicitly pins one. */
    static String resolveMinecraftVersion(Path clientJar, String configuredVersion) throws IOException {
        String actual = clientVersion(clientJar);
        if (configuredVersion == null || configuredVersion.isBlank()) {
            return actual;
        }
        String expected = configuredVersion.trim();
        if (!expected.equals(actual)) {
            throw new IOException("Minecraft client JAR version " + actual
                    + " does not match BlueMap worker version " + expected);
        }
        return expected;
    }

    private static String clientVersion(Path clientJar) throws IOException {
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            var entry = zip.getEntry("version.json");
            if (entry == null) throw new IOException("Minecraft client JAR does not contain version.json");
            try (var input = zip.getInputStream(entry)) {
                JsonNode root = JSON.readTree(input);
                String actualVersion = root == null ? null : root.path("id").asText(null);
                if (actualVersion == null || actualVersion.isBlank()) {
                    throw new IOException("Minecraft client JAR version.json does not contain id");
                }
                return actualVersion;
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
        command.add("-r");
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

    /** Keeps the actionable worker failure after the task directory and full log are removed. */
    static String logTail(Path logFile) {
        try {
            String content = Files.readString(logFile).replace('\u0000', ' ').trim();
            if (content.length() <= MAX_FAILURE_LOG_CHARS) return content.isEmpty() ? "no worker output" : content;
            return content.substring(content.length() - MAX_FAILURE_LOG_CHARS);
        } catch (IOException ignored) {
            return "worker log unavailable";
        }
    }
}
