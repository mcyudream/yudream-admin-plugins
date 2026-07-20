package online.yudream.plugin.worldmap.infrastructure.world.anvil;

import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtTag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用 chunk NBT 构造工具：按 1.18+ sections 格式生成调色板、
 * packed long 数据（1.16+ 不跨 long 边界）与光照 nibble 数组。
 */
public final class ChunkFixtures {

    private ChunkFixtures() {
    }

    /** 方块调色板位宽：最小 4 bit。 */
    public static int bitsForBlocks(int paletteSize) {
        return Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
    }

    /** 生物群系调色板位宽：最小 1 bit。 */
    public static int bitsForBiomes(int paletteSize) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
    }

    /** 方块在 section 内的线性索引。 */
    public static int blockIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    /** 生物群系（4×4×4 粒度）在 section 内的线性索引。 */
    public static int biomeIndex(int bx, int by, int bz) {
        return (by << 4) | (bz << 2) | bx;
    }

    /** 把索引数组按位宽打包为 long 数组（不跨 long 边界填充）。 */
    public static long[] pack(int[] indices, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(indices.length + perLong - 1) / perLong];
        for (int i = 0; i < indices.length; i++) {
            out[i / perLong] |= ((long) indices[i]) << ((i % perLong) * bits);
        }
        return out;
    }

    /** 在 2048 字节 nibble 数组中设置某方块索引的光照值。 */
    public static void setNibble(byte[] arr, int blockIndex, int value) {
        int i = blockIndex >> 1;
        if ((blockIndex & 1) == 0) {
            arr[i] = (byte) ((arr[i] & 0xF0) | (value & 0xF));
        } else {
            arr[i] = (byte) ((arr[i] & 0x0F) | ((value & 0xF) << 4));
        }
    }

    /** 构造 palette 条目。 */
    public static NbtTag paletteEntry(String name) {
        return paletteEntry(name, Map.of());
    }

    public static NbtTag paletteEntry(String name, Map<String, String> properties) {
        Map<String, NbtTag> entry = new LinkedHashMap<>();
        entry.put("Name", NbtTag.ofString(name));
        if (!properties.isEmpty()) {
            Map<String, NbtTag> props = new LinkedHashMap<>();
            properties.forEach((k, v) -> props.put(k, NbtTag.ofString(v)));
            entry.put("Properties", NbtTag.compound(props));
        }
        return NbtTag.compound(entry);
    }

    /**
     * 构造一个 section。
     *
     * @param blockIndices 4096 个方块调色板索引；palette 仅 1 项时可传 null（不写 data）
     * @param biomePalette 生物群系调色板；null 表示整个 biomes 缺失
     * @param biomeIndices 64 个群系索引；palette 仅 1 项时可传 null
     */
    public static NbtTag section(int y, List<NbtTag> blockPalette, int[] blockIndices,
                                 byte[] blockLight, byte[] skyLight,
                                 List<String> biomePalette, int[] biomeIndices) {
        Map<String, NbtTag> s = new LinkedHashMap<>();
        s.put("Y", NbtTag.ofByte((byte) y));

        Map<String, NbtTag> blockStates = new LinkedHashMap<>();
        blockStates.put("palette", NbtTag.list(NbtTag.TAG_COMPOUND, blockPalette));
        if (blockIndices != null && blockPalette.size() > 1) {
            blockStates.put("data", NbtTag.ofLongArray(pack(blockIndices, bitsForBlocks(blockPalette.size()))));
        }
        s.put("block_states", NbtTag.compound(blockStates));

        if (biomePalette != null) {
            Map<String, NbtTag> biomes = new LinkedHashMap<>();
            biomes.put("palette", NbtTag.list(NbtTag.TAG_STRING,
                    biomePalette.stream().map(NbtTag::ofString).toList()));
            if (biomeIndices != null && biomePalette.size() > 1) {
                biomes.put("data", NbtTag.ofLongArray(pack(biomeIndices, bitsForBiomes(biomePalette.size()))));
            }
            s.put("biomes", NbtTag.compound(biomes));
        }

        if (blockLight != null) {
            s.put("BlockLight", NbtTag.ofByteArray(blockLight));
        }
        if (skyLight != null) {
            s.put("SkyLight", NbtTag.ofByteArray(skyLight));
        }
        return NbtTag.compound(s);
    }

    /** 构造 chunk 根 NBT（DataVersion 3465，对应 1.20）。 */
    public static NbtTag chunk(int x, int z, NbtTag... sections) {
        Map<String, NbtTag> root = new LinkedHashMap<>();
        root.put("DataVersion", NbtTag.ofInt(3465));
        root.put("xPos", NbtTag.ofInt(x));
        root.put("zPos", NbtTag.ofInt(z));
        root.put("sections", NbtTag.list(NbtTag.TAG_COMPOUND, List.of(sections)));
        return NbtTag.compound(root);
    }
}
