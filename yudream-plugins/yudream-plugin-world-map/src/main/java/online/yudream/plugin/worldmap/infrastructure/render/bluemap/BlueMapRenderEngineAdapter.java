package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import online.yudream.plugin.worldmap.application.service.GenerationPublisher;
import online.yudream.plugin.worldmap.domain.aggregate.MapGeneration;
import online.yudream.plugin.worldmap.infrastructure.render.ProgressListener;
import online.yudream.plugin.worldmap.infrastructure.render.RenderJob;
import online.yudream.plugin.worldmap.infrastructure.render.RenderSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs an isolated BlueMap v5.16 CLI and imports validated PRBM/lowres output into a generation. */
public final class BlueMapRenderEngineAdapter {

    private final BlueMapCliLocator locator;
    private final BlueMapFileGenerationImporter importer;
    private final BlueMapBundledRuntime bundledRuntime;
    private volatile BlueMapCliRenderEngine activeWorker;

    public BlueMapRenderEngineAdapter() {
        this(new BlueMapCliLocator(), new BlueMapFileGenerationImporter(), new BlueMapBundledRuntime());
    }

    BlueMapRenderEngineAdapter(BlueMapCliLocator locator, BlueMapFileGenerationImporter importer) {
        this(locator, importer, new BlueMapBundledRuntime());
    }

    BlueMapRenderEngineAdapter(BlueMapCliLocator locator, BlueMapFileGenerationImporter importer,
                               BlueMapBundledRuntime bundledRuntime) {
        this.locator = locator;
        this.importer = importer;
        this.bundledRuntime = bundledRuntime;
    }

    public RenderSummary render(RenderJob job, String blueMapMapId, Path workDir, MapGeneration generation,
                                GenerationPublisher publisher, BlueMapRenderConfiguration config,
                                ProgressListener progress) throws IOException {
        Path cli = config.cliJar();
        Path template = config.configTemplate();
        if (config.bundledRuntime()) {
            BlueMapBundledRuntime.Materialized runtime = bundledRuntime.materialize(workDir);
            cli = runtime.cliJar();
            template = runtime.configTemplate();
        }
        cli = locator.verify(cli);
        if (progress != null) {
            progress.phase(online.yudream.plugin.worldmap.domain.enumerate.RenderPhase.HIRES, "BlueMap rendering detailed tiles");
        }
        BlueMapCliRenderEngine worker = new BlueMapCliRenderEngine(config.javaExecutable(), cli, template,
                config.maxHeapMiB(), config.timeout(), config.renderThreadCount());
        activeWorker = worker;
        Path storageParent = resolveStorageParent(workDir, config.storageRoot());
        String minecraftVersion = BlueMapCliRenderEngine.resolveMinecraftVersion(job.clientJar(), config.minecraftVersion());
        Path resourceDataRoot = resolveResourceDataRoot(workDir, config.resourceCacheRoot(), minecraftVersion);
        String workerMapId = BlueMapCliRenderEngine.blueMapMapId(blueMapMapId);
        int totalHiresTiles = Math.toIntExact(job.tileManifest().tileCount());
        try {
            worker.render(workDir, workerMapId, minecraftVersion, job.worldDir(), job.clientJar(), job.dimension(),
                    storageParent, resourceDataRoot, percent -> publishProgress(progress, percent, totalHiresTiles));
        } finally {
            activeWorker = null;
        }
        Path storageRoot = requireStorageRoot(workDir, storageParent, workerMapId);
        if (progress != null) {
            progress.phase(online.yudream.plugin.worldmap.domain.enumerate.RenderPhase.LOWRES, "Importing BlueMap output");
        }
        BlueMapImportSummary imported = importer.importStorage(storageRoot, generation, publisher);
        requireCompleteHiresCoverage(job, imported);
        return new RenderSummary(imported.hiresTiles(), imported.lowresTiles(), 0, 0);
    }

    /** Interrupts the currently running isolated BlueMap CLI, if any. */
    public void cancel() {
        BlueMapCliRenderEngine worker = activeWorker;
        if (worker != null) worker.cancel();
    }

    /** A published BlueMap generation must cover every populated 32x32 world tile. */
    static void requireCompleteHiresCoverage(RenderJob job, BlueMapImportSummary imported) throws IOException {
        long expected = job.tileManifest().tileCount();
        if (imported.hiresTiles() < expected) {
            throw new IOException("BlueMap emitted only " + imported.hiresTiles() + " detailed tiles; expected at least "
                    + expected + " for the uploaded world. The partial generation was not published.");
        }
    }

    private static void publishProgress(ProgressListener progress, double percent, int totalHiresTiles) {
        if (progress == null) return;
        int completed = (int) Math.min(totalHiresTiles, Math.floor(totalHiresTiles * percent / 100d));
        progress.progress(completed, totalHiresTiles,
                "BlueMap rendering detailed tiles (" + String.format(java.util.Locale.ROOT, "%.1f", percent) + "%)");
    }

    private Path resolveStorageParent(Path workDir, Path configured) throws IOException {
        Path root = configured.isAbsolute() ? configured : workDir.resolve(configured).normalize();
        if (!root.startsWith(workDir.toAbsolutePath().normalize())) {
            throw new IOException("BlueMap storage root is not a task-local directory: " + root);
        }
        return root;
    }

    private Path requireStorageRoot(Path workDir, Path storageParent, String mapId) throws IOException {
        Path mapRoot = storageParent.resolve(mapId).normalize();
        if (!mapRoot.startsWith(workDir.toAbsolutePath().normalize()) || !Files.isDirectory(mapRoot)) {
            throw new IOException("BlueMap storage root is not a task-local map directory: " + mapRoot);
        }
        return mapRoot;
    }

    /**
     * A configured cache is the only state shared between render jobs. It contains BlueMap's
     * downloaded renderer resources, while worlds, output, logs and configuration remain task-local.
     */
    private Path resolveResourceDataRoot(Path workDir, Path configured, String minecraftVersion) throws IOException {
        if (configured == null) {
            return workDir.resolve("data");
        }
        Path root = configured.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) && !Files.exists(root)) {
            Files.createDirectories(root);
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("BlueMap resource cache root is not a directory: " + root);
        }
        Path versionRoot = root.resolve(minecraftVersion).normalize();
        if (!versionRoot.startsWith(root)) {
            throw new IOException("BlueMap resource cache version path is invalid");
        }
        Files.createDirectories(versionRoot);
        return versionRoot;
    }
}
