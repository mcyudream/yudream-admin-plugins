package online.yudream.plugin.worldmap.infrastructure.world.anvil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 测试用最小 .mca 写入器：把若干 chunk（已压缩字节）按 Anvil 布局落盘，
 * 生成合法的 location / timestamp 表与 4KiB 扇区对齐数据区。
 */
public final class MCAWriter {

    /** 一个待写入的 chunk。 */
    public record Entry(int localX, int localZ, int compressionType, byte[] compressed, int timestamp) {
    }

    private static final int SECTOR_BYTES = 4096;

    private MCAWriter() {
    }

    public static void writeRegion(Path path, List<Entry> entries) throws IOException {
        // 头两个扇区：location 表 + timestamp 表
        ByteBuffer header = ByteBuffer.allocate(SECTOR_BYTES * 2);
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        int sector = 2; // 数据区从第 2 扇区开始
        for (Entry e : entries.stream().sorted(Comparator.comparingInt(e -> e.localX + e.localZ * 32)).toList()) {
            int payloadLen = 5 + e.compressed.length; // 4 字节长度 + 1 字节压缩类型 + 数据
            int sectors = (payloadLen + SECTOR_BYTES - 1) / SECTOR_BYTES;
            int idx = e.localX + e.localZ * 32;

            // location 表：3 字节大端扇区偏移 + 1 字节扇区数
            header.put(idx * 4, (byte) (sector >>> 16));
            header.put(idx * 4 + 1, (byte) (sector >>> 8));
            header.put(idx * 4 + 2, (byte) sector);
            header.put(idx * 4 + 3, (byte) sectors);
            // timestamp 表
            header.putInt(SECTOR_BYTES + idx * 4, e.timestamp);

            // chunk 数据：长度（含压缩类型字节）+ 压缩类型 + 压缩数据 + 扇区填充
            ByteBuffer chunkHeader = ByteBuffer.allocate(5);
            chunkHeader.putInt(e.compressed.length + 1);
            chunkHeader.put((byte) e.compressionType);
            body.write(chunkHeader.array());
            body.write(e.compressed);
            int pad = sectors * SECTOR_BYTES - payloadLen;
            body.write(new byte[pad]);

            sector += sectors;
        }

        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(header.array());
            out.write(body.toByteArray());
        }
    }
}
