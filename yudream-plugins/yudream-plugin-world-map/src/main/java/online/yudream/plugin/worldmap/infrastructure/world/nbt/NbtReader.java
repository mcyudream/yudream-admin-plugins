package online.yudream.plugin.worldmap.infrastructure.world.nbt;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * NBT 读取器：解析 Named Binary Tag 全类型，支持未压缩 / GZIP / ZLIB 输入流。
 */
public final class NbtReader {

    private NbtReader() {
    }

    /**
     * 读取完整 NBT 流（根标签须为 TAG_Compound），按魔数自动嗅探 GZIP（0x1F8B）/
     * ZLIB（0x78）/ 未压缩。
     */
    public static NbtTag read(InputStream raw) throws IOException {
        BufferedInputStream in = raw instanceof BufferedInputStream b ? b : new BufferedInputStream(raw);
        in.mark(2);
        int b0 = in.read();
        int b1 = in.read();
        in.reset();
        InputStream decoded;
        if (b0 == 0x1F && b1 == 0x8B) {
            decoded = new GZIPInputStream(in);
        } else if (b0 == 0x78) {
            // zlib 头第一字节固定为 0x78（不同压缩级别仅第二字节不同）
            decoded = new InflaterInputStream(in);
        } else {
            decoded = in;
        }
        return readRoot(new DataInputStream(decoded));
    }

    /** 解析未压缩的完整 NBT 字节（含根标签类型与名称）。 */
    public static NbtTag parse(byte[] uncompressed) throws IOException {
        return readRoot(new DataInputStream(new ByteArrayInputStream(uncompressed)));
    }

    /** 从流中读取根标签（类型 + 名称 + 载荷），根标签必须是 TAG_Compound。 */
    public static NbtTag readRoot(DataInput in) throws IOException {
        int type = in.readByte() & 0xFF;
        if (type != NbtTag.TAG_COMPOUND) {
            throw new IOException("根标签必须是 TAG_Compound，实际类型: " + type);
        }
        in.readUTF(); // 根名称，忽略
        return readPayload(in, NbtTag.TAG_COMPOUND);
    }

    /** 读取一个标签载荷（不含类型与名称），供列表 / 复合内部复用。 */
    private static NbtTag readPayload(DataInput in, int type) throws IOException {
        return switch (type) {
            case NbtTag.TAG_BYTE -> NbtTag.ofByte(in.readByte());
            case NbtTag.TAG_SHORT -> NbtTag.ofShort(in.readShort());
            case NbtTag.TAG_INT -> NbtTag.ofInt(in.readInt());
            case NbtTag.TAG_LONG -> NbtTag.ofLong(in.readLong());
            case NbtTag.TAG_FLOAT -> NbtTag.ofFloat(in.readFloat());
            case NbtTag.TAG_DOUBLE -> NbtTag.ofDouble(in.readDouble());
            case NbtTag.TAG_BYTE_ARRAY -> {
                byte[] arr = new byte[in.readInt()];
                in.readFully(arr);
                yield NbtTag.ofByteArray(arr);
            }
            case NbtTag.TAG_STRING -> NbtTag.ofString(in.readUTF());
            case NbtTag.TAG_LIST -> {
                int elementType = in.readByte() & 0xFF;
                int length = in.readInt();
                List<NbtTag> elements = new ArrayList<>(Math.max(0, Math.min(length, 1 << 20)));
                for (int i = 0; i < length; i++) {
                    elements.add(readPayload(in, elementType));
                }
                yield NbtTag.list(elementType, elements);
            }
            case NbtTag.TAG_COMPOUND -> {
                Map<String, NbtTag> entries = new LinkedHashMap<>();
                int childType;
                while ((childType = in.readByte() & 0xFF) != NbtTag.TAG_END) {
                    String name = in.readUTF();
                    entries.put(name, readPayload(in, childType));
                }
                yield NbtTag.compound(entries);
            }
            case NbtTag.TAG_INT_ARRAY -> {
                int length = in.readInt();
                int[] arr = new int[length];
                for (int i = 0; i < length; i++) {
                    arr[i] = in.readInt();
                }
                yield NbtTag.ofIntArray(arr);
            }
            case NbtTag.TAG_LONG_ARRAY -> {
                int length = in.readInt();
                long[] arr = new long[length];
                for (int i = 0; i < length; i++) {
                    arr[i] = in.readLong();
                }
                yield NbtTag.ofLongArray(arr);
            }
            default -> throw new IOException("未知 NBT 标签类型: " + type);
        };
    }
}
