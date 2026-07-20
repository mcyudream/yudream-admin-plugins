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
    private volatile BlueMapCliRenderEngine activeWorker;

    public BlueMapRenderEngineAdapter() {
        this(new BlueMapCliLocator(), new BlueMapFileGenerationImporter());
    }

    BlueMapRenderEngineAdapter(BlueMapCliLocator locator, BlueMapFileGenerationImporter importer) {
        this.locator = locator;
        this.importer = importer;
    }

    public RenderSummary render(RenderJob job, String blueMapMapId, Path workDir, MapGeneration generation,
                                GenerationPublisher publisher, BlueMapRenderConfiguration config,
                                ProgressListener progress) throws IOException {
        Path cli = locator.verify(config.cliJar());
        if (progress != null) {
            progress.phase(online.yudream.plugin.worldmap.domain.enumerate.RenderPhase.HIRES, "BlueMap rendering detailed tiles");
        }
        BlueMapCliRenderEngine worker = new BlueMapCliRenderEngine(config.javaExecutable(), cli, config.configTemplate(),
                config.maxHeapMiB(), config.timeout());
        activeWorker = worker;
        Path storageParent = resolveStorageParent(workDir, config.storageRoot());
        Path resourceDataRoot = resolveResourceDataRoot(workDir, config.resourceCacheRoot(), config.minecraftVersion());
        String workerMapId = BlueMapCliRenderEngine.blueMapMapId(blueMapMapId);
        try {
            worker.render(workDir, workerMapId, config.minecraftVersion(), job.worldDir(), job.clientJar(), job.dimension(),
                    storageParent, resourceDataRoot);
        } finally {
            activeWorker = null;
        }
        Path storageRoot = requireStorageRoot(workDir, storageParent, workerMapId);
        if (progress != null) {
            progress.phase(online.yudream.plugin.worldmap.domain.enumerate.RenderPhase.LOWRES, "Importing BlueMap output");
        }
        BlueMapImportSummary imported = importer.importStorage(storageRoot, generation, publisher);
        return new RenderSummary(imported.hiresTiles(), imported.lowresTiles(), 0, 0);
    }

    /** Interrupts the currently running isolated BlueMap CLI, if any. */
    public void cancel() {
        BlueMapCliRenderEngine worker = activeWorker;
        if (worker != null) worker.cancel();
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
