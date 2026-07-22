package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import java.nio.file.Path;
import java.time.Duration;

/** Validated configuration for an isolated BlueMap engine. */
public record BlueMapRenderConfiguration(Path javaExecutable, Path cliJar, Path configTemplate,
                                         Path storageRoot, int maxHeapMiB, Duration timeout,
                                         String minecraftVersion, Path resourceCacheRoot,
                                         boolean bundledRuntime, int renderThreadCount) {
}
