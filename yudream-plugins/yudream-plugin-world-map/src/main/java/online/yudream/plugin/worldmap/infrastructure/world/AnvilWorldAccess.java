package online.yudream.plugin.worldmap.infrastructure.world;

import online.yudream.plugin.worldmap.infrastructure.world.anvil.ChunkData;
import online.yudream.plugin.worldmap.infrastructure.world.anvil.ChunkDecoder;
import online.yudream.plugin.worldmap.infrastructure.world.anvil.RegionFile;
import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtTag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 Anvil region 目录的 {@link WorldAccess} 实现。
 * region 文件与解码后 chunk 均采用有界 LRU 缓存；region 文件只按需随机读取，
 * 不会整体载入内存。所有公共方法同步，保证缓存与文件句柄的线程安全。
 */
final class AnvilWorldAccess implements WorldAccess {

    /** region 文件句柄缓存上限。 */
    private static final int MAX_REGION_FILES = 16;
    /** 解码 chunk 缓存上限。 */
    private static final int MAX_CHUNKS = 256;

    /** 默认生物群系（数据缺失时）。 */
    private static final String DEFAULT_BIOME = "minecraft:plains";

    private final Path regionDir;

    /** region 文件句柄 LRU（含负缓存），淘汰时关闭文件。 */
    private final Map<Long, Optional<RegionFile>> regions =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Optional<RegionFile>> eldest) {
                    if (size() > MAX_REGION_FILES) {
                        eldest.getValue().ifPresent(r -> {
                            try {
                                r.close();
                            } catch (IOException ignored) {
                                // 关闭失败不影响淘汰
                            }
                        });
                        return true;
                    }
                    return false;
                }
            };

    /** 解码 chunk LRU（含负缓存）。 */
    private final Map<Long, Optional<ChunkData>> chunks =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Optional<ChunkData>> eldest) {
                    return size() > MAX_CHUNKS;
                }
            };

    /** 已观测到的高度范围；未加载任何 chunk 前为 null，取默认值。 */
    private Integer observedMinY;
    private Integer observedMaxBuildY;

    private AnvilWorldAccess(Path regionDir) {
        this.regionDir = regionDir;
    }

    static AnvilWorldAccess open(Path regionDir) {
        return new AnvilWorldAccess(regionDir);
    }

    @Override
    public synchronized BlockState blockState(int x, int y, int z) {
        ChunkData chunk = chunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (chunk == null) {
            return BlockState.AIR;
        }
        ChunkData.Section section = chunk.sectionAt(y);
        if (section == null) {
            return BlockState.AIR;
        }
        return section.blockAt(x & 15, y & 15, z & 15);
    }

    @Override
    public synchronized int blockLight(int x, int y, int z) {
        ChunkData.Section section = sectionAt(x, y, z);
        if (section == null) {
            return 0;
        }
        int v = section.blockLightAt(x & 15, y & 15, z & 15);
        return v < 0 ? 0 : v;
    }

    @Override
    public synchronized int skyLight(int x, int y, int z) {
        ChunkData.Section section = sectionAt(x, y, z);
        if (section == null) {
            return 15;
        }
        int v = section.skyLightAt(x & 15, y & 15, z & 15);
        return v < 0 ? 15 : v;
    }

    @Override
    public synchronized String biome(int x, int y, int z) {
        ChunkData.Section section = sectionAt(x, y, z);
        if (section == null) {
            return DEFAULT_BIOME;
        }
        String biome = section.biomeAt(x & 15, y & 15, z & 15);
        return biome != null ? biome : DEFAULT_BIOME;
    }

    @Override
    public synchronized int maxY(int x, int z) {
        ChunkData chunk = chunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (chunk == null) {
            return minY();
        }
        for (ChunkData.Section section : chunk.sectionsTopDown()) {
            for (int ly = 15; ly >= 0; ly--) {
                if (!section.blockAt(x & 15, ly, z & 15).isAir()) {
                    return section.y() * 16 + ly;
                }
            }
        }
        return minY();
    }

    @Override
    public synchronized int minY() {
        return observedMinY != null ? observedMinY : -64;
    }

    @Override
    public synchronized int maxBuildY() {
        return observedMaxBuildY != null ? observedMaxBuildY : 320;
    }

    private ChunkData.Section sectionAt(int x, int y, int z) {
        ChunkData chunk = chunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        return chunk != null ? chunk.sectionAt(y) : null;
    }

    /** 取解码后的 chunk（带缓存），未生成或读取失败返回 null。 */
    private ChunkData chunkAt(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        Optional<ChunkData> cached = chunks.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        ChunkData decoded = loadChunk(chunkX, chunkZ);
        chunks.put(key, Optional.ofNullable(decoded));
        if (decoded != null) {
            observedMinY = observedMinY == null ? decoded.minY() : Math.min(observedMinY, decoded.minY());
            observedMaxBuildY = observedMaxBuildY == null ? decoded.maxBuildY()
                    : Math.max(observedMaxBuildY, decoded.maxBuildY());
        }
        return decoded;
    }

    private ChunkData loadChunk(int chunkX, int chunkZ) {
        try {
            RegionFile region = regionAt(Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
            if (region == null) {
                return null;
            }
            NbtTag root = region.readChunk(chunkX & 31, chunkZ & 31);
            return root != null ? ChunkDecoder.decode(root) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("读取 chunk 失败: " + chunkX + "," + chunkZ, e);
        }
    }

    private RegionFile regionAt(int regionX, int regionZ) {
        long key = chunkKey(regionX, regionZ);
        Optional<RegionFile> cached = regions.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        RegionFile region = null;
        Path path = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (Files.isRegularFile(path)) {
            try {
                region = RegionFile.open(path);
            } catch (IOException e) {
                throw new UncheckedIOException("打开 region 文件失败: " + path, e);
            }
        }
        regions.put(key, Optional.ofNullable(region));
        return region;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @Override
    public synchronized void close() {
        regions.values().forEach(r -> r.ifPresent(region -> {
            try {
                region.close();
            } catch (IOException ignored) {
                // 关闭失败忽略
            }
        }));
        regions.clear();
        chunks.clear();
    }
}
