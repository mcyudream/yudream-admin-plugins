package online.yudream.plugin.worldmap.infrastructure.world;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, sparse set of 32x32-block hires tiles that contain world data.
 */
public final class WorldTileManifest {

    public record Tile(int x, int z) {
    }

    private static final Comparator<Tile> TILE_ORDER = Comparator.comparingInt(Tile::x)
            .thenComparingInt(Tile::z);

    private final List<Tile> tiles;
    private final int minTileX;
    private final int minTileZ;
    private final int maxTileX;
    private final int maxTileZ;

    private WorldTileManifest(List<Tile> tiles) {
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("World tile manifest cannot be empty");
        }
        this.tiles = tiles;
        this.minTileX = tiles.stream().mapToInt(Tile::x).min().orElseThrow();
        this.minTileZ = tiles.stream().mapToInt(Tile::z).min().orElseThrow();
        this.maxTileX = tiles.stream().mapToInt(Tile::x).max().orElseThrow();
        this.maxTileZ = tiles.stream().mapToInt(Tile::z).max().orElseThrow();
    }

    public static WorldTileManifest of(Collection<Tile> tiles) {
        Objects.requireNonNull(tiles, "tiles");
        List<Tile> ordered = tiles.stream().distinct().sorted(TILE_ORDER).toList();
        return new WorldTileManifest(ordered);
    }

    /** Compatibility helper for callers that intentionally need a full rectangle. */
    public static WorldTileManifest rectangular(int minTileX, int minTileZ, int maxTileX, int maxTileZ) {
        if (minTileX > maxTileX || minTileZ > maxTileZ) {
            throw new IllegalArgumentException("Invalid tile bounds");
        }
        java.util.ArrayList<Tile> tiles = new java.util.ArrayList<>();
        for (int tx = minTileX; tx <= maxTileX; tx++) {
            for (int tz = minTileZ; tz <= maxTileZ; tz++) {
                tiles.add(new Tile(tx, tz));
            }
        }
        return new WorldTileManifest(tiles);
    }

    public List<Tile> tiles() { return tiles; }
    public long tileCount() { return tiles.size(); }
    public boolean contains(int x, int z) { return tiles.contains(new Tile(x, z)); }
    public int minTileX() { return minTileX; }
    public int minTileZ() { return minTileZ; }
    public int maxTileX() { return maxTileX; }
    public int maxTileZ() { return maxTileZ; }
}
