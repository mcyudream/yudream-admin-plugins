package online.yudream.plugin.worldmap.infrastructure.world.anvil;

import online.yudream.plugin.worldmap.infrastructure.world.BlockState;
import online.yudream.plugin.worldmap.infrastructure.world.nbt.NbtTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * chunk NBT（1.18+，DataVersion 3465+）→ {@link ChunkData} 解码器。
 * 读取 sections[] 中的 block_states / biomes 调色板与 packed long 数据、
 * BlockLight / SkyLight nibble 数组；高度范围由 section Y 推导。
 */
public final class ChunkDecoder {

    private ChunkDecoder() {
    }

    public static ChunkData decode(NbtTag root) {
        int x = root.getInt("xPos", 0);
        int z = root.getInt("zPos", 0);

        List<ChunkData.Section> sections = new ArrayList<>();
        NbtTag sectionsTag = root.get("sections");
        if (sectionsTag != null && sectionsTag.type() == NbtTag.TAG_LIST) {
            for (NbtTag s : sectionsTag.asList()) {
                sections.add(decodeSection(s));
            }
        }
        sections.sort(Comparator.comparingInt(s -> s.y));
        return new ChunkData(x, z, sections.toArray(ChunkData.Section[]::new));
    }

    private static ChunkData.Section decodeSection(NbtTag s) {
        int y = s.getByte("Y", (byte) 0);

        // 方块调色板 + 打包数据
        BlockState[] palette = new BlockState[0];
        long[] blockData = new long[0];
        NbtTag blockStates = s.get("block_states");
        if (blockStates != null) {
            NbtTag paletteTag = blockStates.get("palette");
            if (paletteTag != null && paletteTag.type() == NbtTag.TAG_LIST) {
                List<NbtTag> entries = paletteTag.asList();
                palette = new BlockState[entries.size()];
                for (int i = 0; i < entries.size(); i++) {
                    palette[i] = decodeBlockState(entries.get(i));
                }
            }
            NbtTag dataTag = blockStates.get("data");
            if (dataTag != null && dataTag.type() == NbtTag.TAG_LONG_ARRAY) {
                blockData = dataTag.asLongArray();
            }
        }

        // 生物群系调色板 + 打包数据
        String[] biomePalette = new String[0];
        long[] biomeData = new long[0];
        NbtTag biomes = s.get("biomes");
        if (biomes != null) {
            NbtTag paletteTag = biomes.get("palette");
            if (paletteTag != null && paletteTag.type() == NbtTag.TAG_LIST) {
                List<NbtTag> entries = paletteTag.asList();
                biomePalette = new String[entries.size()];
                for (int i = 0; i < entries.size(); i++) {
                    biomePalette[i] = entries.get(i).asString();
                }
            }
            NbtTag dataTag = biomes.get("data");
            if (dataTag != null && dataTag.type() == NbtTag.TAG_LONG_ARRAY) {
                biomeData = dataTag.asLongArray();
            }
        }

        NbtTag blockLight = s.get("BlockLight");
        NbtTag skyLight = s.get("SkyLight");

        return new ChunkData.Section(
                y,
                palette, blockData, bitsFor(palette.length, 4),
                biomePalette, biomeData, bitsFor(biomePalette.length, 1),
                blockLight != null && blockLight.type() == NbtTag.TAG_BYTE_ARRAY ? blockLight.asByteArray() : null,
                skyLight != null && skyLight.type() == NbtTag.TAG_BYTE_ARRAY ? skyLight.asByteArray() : null);
    }

    private static BlockState decodeBlockState(NbtTag entry) {
        String name = entry.getString("Name", "minecraft:air");
        Map<String, String> properties = new LinkedHashMap<>();
        NbtTag props = entry.get("Properties");
        if (props != null && props.type() == NbtTag.TAG_COMPOUND) {
            for (Map.Entry<String, NbtTag> e : props.asCompound().entrySet()) {
                properties.put(e.getKey(), e.getValue().asString());
            }
        }
        return new BlockState(name, properties);
    }

    /**
     * 调色板索引位宽：2^bits >= paletteSize 的最小 bits，且不小于 minBits。
     * 方块最小 4 bit，生物群系最小 1 bit。
     */
    static int bitsFor(int paletteSize, int minBits) {
        int bits = Math.max(minBits, 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1)));
        return Math.min(bits, 64);
    }
}
