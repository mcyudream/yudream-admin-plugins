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
        Path storageParent = resolveStorageParent(workDir, config.storageRoot());
        String workerMapId = BlueMapCliRenderEngine.blueMapMapId(blueMapMapId);
        worker.render(workDir, workerMapId, config.minecraftVersion(), job.worldDir(), job.clientJar(), job.dimension(),
                storageParent);
        Path storageRoot = requireStorageRoot(workDir, storageParent, workerMapId);
        if (progress != null) {
            progress.phase(online.yudream.plugin.worldmap.domain.enumerate.RenderPhase.LOWRES, "Importing BlueMap output");
        }
        BlueMapImportSummary imported = importer.importStorage(storageRoot, generation, publisher);
        return new RenderSummary(imported.hiresTiles(), imported.lowresTiles(), 0, 0);
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
}
