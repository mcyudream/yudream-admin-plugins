package online.yudream.plugin.worldmap.infrastructure.world;

import online.yudream.plugin.worldmap.infrastructure.world.anvil.MCAWriter;
import online.yudream.plugin.worldmap.infrastructure.world.anvil.RegionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldArchiveTest {

    @TempDir
    Path worldRoot;

    @Test
    void manifestContainsOnlyTilesWithPopulatedChunksAcrossDistantRegions() throws Exception {
        Path regionDir = Files.createDirectories(worldRoot.resolve("region"));
        MCAWriter.writeRegion(regionDir.resolve("r.0.0.mca"), List.of(
                new MCAWriter.Entry(0, 0, RegionFile.COMPRESSION_NONE, new byte[]{1}, 1)));
        MCAWriter.writeRegion(regionDir.resolve("r.10.-3.mca"), List.of(
                new MCAWriter.Entry(31, 0, RegionFile.COMPRESSION_NONE, new byte[]{1}, 1)));

        WorldTileManifest manifest = WorldArchive.tileManifest(worldRoot, "overworld");

        assertEquals(2L, manifest.tileCount());
        assertTrue(manifest.contains(0, 0));
        assertTrue(manifest.contains(175, -48));
        assertFalse(manifest.contains(1, 0));
        assertFalse(manifest.contains(100, -48));
    }

    @Test
    void resolvesTheWorldRootInsteadOfAnEmbeddedDimensionDirectory() throws Exception {
        Files.writeString(worldRoot.resolve("level.dat"), "level");
        Path overworld = Files.createDirectories(worldRoot.resolve("region"));
        Path nether = Files.createDirectories(worldRoot.resolve("DIM-1/region"));
        MCAWriter.writeRegion(overworld.resolve("r.0.0.mca"), List.of(
                new MCAWriter.Entry(0, 0, RegionFile.COMPRESSION_NONE, new byte[]{1}, 1)));
        MCAWriter.writeRegion(nether.resolve("r.0.0.mca"), List.of(
                new MCAWriter.Entry(0, 0, RegionFile.COMPRESSION_NONE, new byte[]{1}, 1)));

        assertEquals(worldRoot, WorldArchive.resolveWorldRoot(worldRoot));
    }
}
