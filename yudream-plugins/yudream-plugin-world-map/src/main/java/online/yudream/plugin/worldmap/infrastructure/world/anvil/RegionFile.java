package online.yudream.plugin.worldmap.infrastructure.world.anvil;

import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtReader;
import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtTag;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Anvil region 文件（.mca）读取器。
 * 打开时仅解析 8KiB 头部（location / timestamp 表），chunk 数据按需惰性读取，
 * 不会一次性载入整个 region。
 */
public final class RegionFile implements Closeable {

    /** 压缩类型：GZIP。 */
    public static final int COMPRESSION_GZIP = 1;
    /** 压缩类型：ZLIB（deflate），存档最常见。 */
    public static final int COMPRESSION_ZLIB = 2;
    /** 压缩类型：未压缩。 */
    public static final int COMPRESSION_NONE = 3;

    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_ENTRIES = 1024;

    private final RandomAccessFile file;
    /** 每格 chunk 的扇区偏移（4KiB 扇区号），0 表示不存在。 */
    private final int[] offsets = new int[HEADER_ENTRIES];
    /** 每格 chunk 的扇区长度。 */
    private final int[] sectorCounts = new int[HEADER_ENTRIES];
    /** 每格 chunk 的最后修改时间戳（秒）。 */
    private final int[] timestamps = new int[HEADER_ENTRIES];

    private RegionFile(RandomAccessFile file) {
        this.file = file;
    }

    /** 打开 region 文件并读取头部表。 */
    public static RegionFile open(Path path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
        try {
            RegionFile region = new RegionFile(raf);
            if (raf.length() >= SECTOR_BYTES * 2L) {
                for (int i = 0; i < HEADER_ENTRIES; i++) {
                    int entry = raf.readInt();
                    region.offsets[i] = entry >>> 8;
                    region.sectorCounts[i] = entry & 0xFF;
                }
                for (int i = 0; i < HEADER_ENTRIES; i++) {
                    region.timestamps[i] = raf.readInt();
                }
            }
            return region;
        } catch (IOException e) {
            raf.close();
            throw e;
        }
    }

    private static int index(int localX, int localZ) {
        if (localX < 0 || localX > 31 || localZ < 0 || localZ > 31) {
            throw new IllegalArgumentException("region 内坐标越界: " + localX + "," + localZ);
        }
        return localX + localZ * 32;
    }

    /** 该位置是否存在 chunk。 */
    public boolean hasChunk(int localX, int localZ) {
        return offsets[index(localX, localZ)] != 0;
    }

    /** chunk 最后修改时间戳（秒），不存在返回 0。 */
    public int timestamp(int localX, int localZ) {
        return timestamps[index(localX, localZ)];
    }

    /**
     * 读取并解压、解析指定 chunk 的 NBT 根标签；不存在返回 null。
     * 同步方法：同一 region 的随机读取串行进行。
     */
    public synchronized NbtTag readChunk(int localX, int localZ) throws IOException {
        int i = index(localX, localZ);
        int offset = offsets[i];
        if (offset == 0) {
            return null;
        }
        file.seek((long) offset * SECTOR_BYTES);
        int length = file.readInt();
        if (length <= 0 || length > sectorCounts[i] * SECTOR_BYTES) {
            throw new IOException("chunk 长度非法: " + length + " (region 内 " + localX + "," + localZ + ")");
        }
        int compression = file.readByte() & 0xFF;
        byte[] compressed = new byte[length - 1];
        file.readFully(compressed);
        return NbtReader.parse(decompress(compressed, compression));
    }

    private static byte[] decompress(byte[] data, int compression) throws IOException {
        return switch (compression) {
            case COMPRESSION_GZIP -> new GZIPInputStream(new ByteArrayInputStream(data)).readAllBytes();
            case COMPRESSION_ZLIB -> new InflaterInputStream(new ByteArrayInputStream(data)).readAllBytes();
            case COMPRESSION_NONE -> data;
            default -> throw new IOException("未知 chunk 压缩类型: " + compression);
        };
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
