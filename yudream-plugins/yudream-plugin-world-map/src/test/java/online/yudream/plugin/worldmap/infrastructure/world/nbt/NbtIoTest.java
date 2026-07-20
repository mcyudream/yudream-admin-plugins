package online.yudream.plugin.worldmap.infrastructure.world.nbt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NBT 全类型读写与压缩流自动嗅探测试。 */
class NbtIoTest {

    private static NbtTag allTypes() {
        Map<String, NbtTag> root = new LinkedHashMap<>();
        root.put("byte", NbtTag.ofByte((byte) -7));
        root.put("short", NbtTag.ofShort((short) 12345));
        root.put("int", NbtTag.ofInt(-123456789));
        root.put("long", NbtTag.ofLong(1234567890123456789L));
        root.put("float", NbtTag.ofFloat(3.25f));
        root.put("double", NbtTag.ofDouble(-2.5d));
        root.put("string", NbtTag.ofString("你好 NBT"));
        root.put("byteArray", NbtTag.ofByteArray(new byte[]{1, -2, 3, 127, -128}));
        root.put("intArray", NbtTag.ofIntArray(new int[]{0, Integer.MIN_VALUE, Integer.MAX_VALUE}));
        root.put("longArray", NbtTag.ofLongArray(new long[]{0L, Long.MIN_VALUE, Long.MAX_VALUE}));
        root.put("intList", NbtTag.list(NbtTag.TAG_INT,
                List.of(NbtTag.ofInt(1), NbtTag.ofInt(2), NbtTag.ofInt(3))));
        root.put("stringList", NbtTag.list(NbtTag.TAG_STRING,
                List.of(NbtTag.ofString("a"), NbtTag.ofString("b"))));
        root.put("emptyList", NbtTag.list(NbtTag.TAG_END, List.of()));
        Map<String, NbtTag> nested = new LinkedHashMap<>();
        nested.put("name", NbtTag.ofString("nested"));
        nested.put("value", NbtTag.ofDouble(1.5d));
        root.put("compound", NbtTag.compound(nested));
        root.put("compoundList", NbtTag.list(NbtTag.TAG_COMPOUND,
                List.of(NbtTag.compound(nested), NbtTag.compound(nested))));
        return NbtTag.compound(root);
    }

    @Test
    void roundTripAllTypes() throws Exception {
        byte[] bytes = NbtWriter.write("root", allTypes());
        NbtTag read = NbtReader.parse(bytes);

        assertEquals((byte) -7, read.get("byte").asByte());
        assertEquals((short) 12345, read.get("short").asShort());
        assertEquals(-123456789, read.get("int").asInt());
        assertEquals(1234567890123456789L, read.get("long").asLong());
        assertEquals(3.25f, read.get("float").asFloat());
        assertEquals(-2.5d, read.get("double").asDouble());
        assertEquals("你好 NBT", read.get("string").asString());
        assertArrayEquals(new byte[]{1, -2, 3, 127, -128}, read.get("byteArray").asByteArray());
        assertArrayEquals(new int[]{0, Integer.MIN_VALUE, Integer.MAX_VALUE}, read.get("intArray").asIntArray());
        assertArrayEquals(new long[]{0L, Long.MIN_VALUE, Long.MAX_VALUE}, read.get("longArray").asLongArray());

        List<NbtTag> intList = read.get("intList").asList();
        assertEquals(3, intList.size());
        assertEquals(NbtTag.TAG_INT, read.get("intList").listElementType());
        assertEquals(2, intList.get(1).asInt());

        assertEquals(0, read.get("emptyList").asList().size());

        NbtTag nested = read.get("compound");
        assertEquals("nested", nested.getString("name", null));
        assertEquals(1.5d, nested.get("value").asDouble());

        List<NbtTag> compoundList = read.get("compoundList").asList();
        assertEquals(2, compoundList.size());
        assertEquals("nested", compoundList.get(0).getString("name", null));
    }

    @Test
    void readAutoDetectsUncompressed() throws Exception {
        byte[] bytes = NbtWriter.write("root", allTypes());
        NbtTag read = NbtReader.read(new ByteArrayInputStream(bytes));
        assertEquals("你好 NBT", read.getString("string", null));
    }

    @Test
    void readAutoDetectsGzip() throws Exception {
        byte[] raw = NbtWriter.write("root", allTypes());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        }
        NbtTag read = NbtReader.read(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals("你好 NBT", read.getString("string", null));
    }

    @Test
    void readAutoDetectsZlib() throws Exception {
        byte[] raw = NbtWriter.write("root", allTypes());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(bos)) {
            z.write(raw);
        }
        NbtTag read = NbtReader.read(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals("你好 NBT", read.getString("string", null));
    }

    @Test
    void compoundHelpersReturnDefaultsWhenMissing() throws Exception {
        NbtTag tag = NbtReader.parse(NbtWriter.write("root", allTypes()));
        assertTrue(tag.has("int"));
        assertEquals(42, tag.getInt("missing", 42));
        assertEquals("fallback", tag.getString("missing", "fallback"));
        assertEquals((byte) 9, tag.getByte("missing", (byte) 9));
    }
}
