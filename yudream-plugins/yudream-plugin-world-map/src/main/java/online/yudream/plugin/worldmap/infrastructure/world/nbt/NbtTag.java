package online.yudream.plugin.worldmap.infrastructure.world.nbt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 NBT 标签值对象（不可变）。按 {@link #type()} 持有对应 Java 值：
 * 数值类型 → Number，字符串 → String，列表 → List&lt;NbtTag&gt;，
 * 复合 → Map&lt;String, NbtTag&gt;，数组 → byte[]/int[]/long[]。
 */
public final class NbtTag {

    public static final int TAG_END = 0;
    public static final int TAG_BYTE = 1;
    public static final int TAG_SHORT = 2;
    public static final int TAG_INT = 3;
    public static final int TAG_LONG = 4;
    public static final int TAG_FLOAT = 5;
    public static final int TAG_DOUBLE = 6;
    public static final int TAG_BYTE_ARRAY = 7;
    public static final int TAG_STRING = 8;
    public static final int TAG_LIST = 9;
    public static final int TAG_COMPOUND = 10;
    public static final int TAG_INT_ARRAY = 11;
    public static final int TAG_LONG_ARRAY = 12;

    private final int type;
    /** 仅 TAG_LIST 有意义：列表元素类型 id，其余类型为 TAG_END。 */
    private final int listElementType;
    private final Object value;

    private NbtTag(int type, int listElementType, Object value) {
        this.type = type;
        this.listElementType = listElementType;
        this.value = value;
    }

    public static NbtTag ofByte(byte v) {
        return new NbtTag(TAG_BYTE, TAG_END, v);
    }

    public static NbtTag ofShort(short v) {
        return new NbtTag(TAG_SHORT, TAG_END, v);
    }

    public static NbtTag ofInt(int v) {
        return new NbtTag(TAG_INT, TAG_END, v);
    }

    public static NbtTag ofLong(long v) {
        return new NbtTag(TAG_LONG, TAG_END, v);
    }

    public static NbtTag ofFloat(float v) {
        return new NbtTag(TAG_FLOAT, TAG_END, v);
    }

    public static NbtTag ofDouble(double v) {
        return new NbtTag(TAG_DOUBLE, TAG_END, v);
    }

    public static NbtTag ofString(String v) {
        return new NbtTag(TAG_STRING, TAG_END, v);
    }

    public static NbtTag ofByteArray(byte[] v) {
        return new NbtTag(TAG_BYTE_ARRAY, TAG_END, v);
    }

    public static NbtTag ofIntArray(int[] v) {
        return new NbtTag(TAG_INT_ARRAY, TAG_END, v);
    }

    public static NbtTag ofLongArray(long[] v) {
        return new NbtTag(TAG_LONG_ARRAY, TAG_END, v);
    }

    public static NbtTag list(int elementType, List<NbtTag> elements) {
        return new NbtTag(TAG_LIST, elementType, List.copyOf(elements));
    }

    /** 复合标签；内部复制为 LinkedHashMap 以保持写入顺序。 */
    public static NbtTag compound(Map<String, NbtTag> entries) {
        return new NbtTag(TAG_COMPOUND, TAG_END, new LinkedHashMap<>(entries));
    }

    public int type() {
        return type;
    }

    public int listElementType() {
        return listElementType;
    }

    public byte asByte() {
        return ((Number) value).byteValue();
    }

    public short asShort() {
        return ((Number) value).shortValue();
    }

    public int asInt() {
        return ((Number) value).intValue();
    }

    public long asLong() {
        return ((Number) value).longValue();
    }

    public float asFloat() {
        return ((Number) value).floatValue();
    }

    public double asDouble() {
        return ((Number) value).doubleValue();
    }

    public String asString() {
        return (String) value;
    }

    public byte[] asByteArray() {
        return (byte[]) value;
    }

    public int[] asIntArray() {
        return (int[]) value;
    }

    public long[] asLongArray() {
        return (long[]) value;
    }

    @SuppressWarnings("unchecked")
    public List<NbtTag> asList() {
        return (List<NbtTag>) value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, NbtTag> asCompound() {
        return (Map<String, NbtTag>) value;
    }

    // ---------- 复合标签便捷访问 ----------

    public boolean has(String key) {
        return type == TAG_COMPOUND && asCompound().containsKey(key);
    }

    /** 取子标签，不存在时返回 null。 */
    public NbtTag get(String key) {
        return type == TAG_COMPOUND ? asCompound().get(key) : null;
    }

    public int getInt(String key, int def) {
        NbtTag t = get(key);
        return t != null && t.value instanceof Number ? t.asInt() : def;
    }

    public long getLong(String key, long def) {
        NbtTag t = get(key);
        return t != null && t.value instanceof Number ? t.asLong() : def;
    }

    public byte getByte(String key, byte def) {
        NbtTag t = get(key);
        return t != null && t.value instanceof Number ? t.asByte() : def;
    }

    public String getString(String key, String def) {
        NbtTag t = get(key);
        return t != null && t.type == TAG_STRING ? t.asString() : def;
    }
}
