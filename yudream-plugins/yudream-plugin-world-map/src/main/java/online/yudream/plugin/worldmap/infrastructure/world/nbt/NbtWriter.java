package online.yudream.plugin.worldmap.infrastructure.world.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * NBT 写出器（可选能力，主要供测试装置与调试使用）。
 */
public final class NbtWriter {

    private NbtWriter() {
    }

    /** 序列化为未压缩的完整 NBT 字节（含根标签类型与名称）。 */
    public static byte[] write(String rootName, NbtTag root) {
        if (root.type() != NbtTag.TAG_COMPOUND) {
            throw new IllegalArgumentException("根标签必须是 TAG_Compound");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(NbtTag.TAG_COMPOUND);
            out.writeUTF(rootName);
            writePayload(out, root);
            out.flush();
        } catch (IOException e) {
            // ByteArrayOutputStream 不会抛 IOException
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    private static void writePayload(DataOutput out, NbtTag tag) throws IOException {
        switch (tag.type()) {
            case NbtTag.TAG_BYTE -> out.writeByte(tag.asByte());
            case NbtTag.TAG_SHORT -> out.writeShort(tag.asShort());
            case NbtTag.TAG_INT -> out.writeInt(tag.asInt());
            case NbtTag.TAG_LONG -> out.writeLong(tag.asLong());
            case NbtTag.TAG_FLOAT -> out.writeFloat(tag.asFloat());
            case NbtTag.TAG_DOUBLE -> out.writeDouble(tag.asDouble());
            case NbtTag.TAG_BYTE_ARRAY -> {
                byte[] arr = tag.asByteArray();
                out.writeInt(arr.length);
                out.write(arr);
            }
            case NbtTag.TAG_STRING -> out.writeUTF(tag.asString());
            case NbtTag.TAG_LIST -> {
                out.writeByte(tag.listElementType());
                out.writeInt(tag.asList().size());
                for (NbtTag child : tag.asList()) {
                    writePayload(out, child);
                }
            }
            case NbtTag.TAG_COMPOUND -> {
                for (Map.Entry<String, NbtTag> e : tag.asCompound().entrySet()) {
                    out.writeByte(e.getValue().type());
                    out.writeUTF(e.getKey());
                    writePayload(out, e.getValue());
                }
                out.writeByte(NbtTag.TAG_END);
            }
            case NbtTag.TAG_INT_ARRAY -> {
                int[] arr = tag.asIntArray();
                out.writeInt(arr.length);
                for (int v : arr) {
                    out.writeInt(v);
                }
            }
            case NbtTag.TAG_LONG_ARRAY -> {
                long[] arr = tag.asLongArray();
                out.writeInt(arr.length);
                for (long v : arr) {
                    out.writeLong(v);
                }
            }
            default -> throw new IOException("不可写出的 NBT 标签类型: " + tag.type());
        }
    }
}
