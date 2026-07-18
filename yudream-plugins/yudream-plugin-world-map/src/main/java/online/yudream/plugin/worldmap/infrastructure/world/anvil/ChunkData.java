package online.yudream.plugin.worldmap.infrastructure.world.anvil;

import online.yudream.plugin.worldmap.infrastructure.world.BlockState;

/**
 * 解码后的 chunk（1.18+ sections 格式）。只保留渲染需要的部分：
 * 方块调色板与打包数据、生物群系、双光源；实体 / 方块实体等忽略。
 */
public final class ChunkData {

    /** 一个 16×16×16 section。 */
    public static final class Section {
        final int y;
        final BlockState[] palette;
        final long[] blockData;
        final int blockBits;
        final String[] biomePalette;
        final long[] biomeData;
        final int biomeBits;
        /** 可空：缺失时按默认光照处理。 */
        final byte[] blockLight;
        final byte[] skyLight;

        Section(int y, BlockState[] palette, long[] blockData, int blockBits,
                String[] biomePalette, long[] biomeData, int biomeBits,
                byte[] blockLight, byte[] skyLight) {
            this.y = y;
            this.palette = palette;
            this.blockData = blockData;
            this.blockBits = blockBits;
            this.biomePalette = biomePalette;
            this.biomeData = biomeData;
            this.biomeBits = biomeBits;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }

        /** section 的 Y 索引（世界 y = y*16 .. y*16+15）。 */
        public int y() {
            return y;
        }

        /** section 内局部坐标（0..15）的方块状态。 */
        public BlockState blockAt(int lx, int ly, int lz) {
            if (palette.length == 0) {
                return BlockState.AIR;
            }
            if (palette.length == 1 || blockData.length == 0) {
                return palette[0];
            }
            int i = blockIndex(lx, ly, lz);
            int pi = unpack(blockData, blockBits, i);
            return pi < palette.length ? palette[pi] : BlockState.AIR;
        }

        /** section 内局部坐标（0..15）的生物群系；无数据返回 null。 */
        public String biomeAt(int lx, int ly, int lz) {
            if (biomePalette.length == 0) {
                return null;
            }
            if (biomePalette.length == 1 || biomeData.length == 0) {
                return biomePalette[0];
            }
            // 生物群系粒度为 4×4×4，共 64 格
            int i = ((ly >> 2) << 4) | ((lz >> 2) << 2) | (lx >> 2);
            int pi = unpack(biomeData, biomeBits, i);
            return pi < biomePalette.length ? biomePalette[pi] : null;
        }

        /** 方块光，数组缺失返回 -1（调用方按默认值处理）。 */
        public int blockLightAt(int lx, int ly, int lz) {
            return nibble(blockLight, blockIndex(lx, ly, lz));
        }

        /** 天空光，数组缺失返回 -1。 */
        public int skyLightAt(int lx, int ly, int lz) {
            return nibble(skyLight, blockIndex(lx, ly, lz));
        }
    }

    /** 方块在 section 内的线性索引：y 主序，再 z，再 x。 */
    static int blockIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    /**
     * 从打包 long 数组中取第 i 个索引值。
     * 1.16+ 起每个值完整落在单个 long 内，不跨 long 边界。
     */
    static int unpack(long[] data, int bits, int i) {
        int perLong = 64 / bits;
        long word = data[i / perLong];
        int shift = (i % perLong) * bits;
        return (int) ((word >>> shift) & ((1L << bits) - 1));
    }

    /** 取 nibble：偶数索引在低 4 位，奇数在高 4 位；数组缺失返回 -1。 */
    static int nibble(byte[] arr, int i) {
        if (arr == null) {
            return -1;
        }
        int b = arr[i >> 1];
        return (i & 1) == 0 ? b & 0xF : (b >> 4) & 0xF;
    }

    private final int x;
    private final int z;
    /** 按 section Y 升序。 */
    private final Section[] sections;
    private final int minY;
    private final int maxBuildY;

    ChunkData(int x, int z, Section[] sections) {
        this.x = x;
        this.z = z;
        this.sections = sections;
        if (sections.length == 0) {
            this.minY = 0;
            this.maxBuildY = 0;
        } else {
            this.minY = sections[0].y * 16;
            this.maxBuildY = (sections[sections.length - 1].y + 1) * 16;
        }
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    /** 本 chunk 最低建筑高度。 */
    public int minY() {
        return minY;
    }

    /** 本 chunk 建筑上限（不含）。 */
    public int maxBuildY() {
        return maxBuildY;
    }

    /** 世界坐标 y 所在 section，不存在返回 null。 */
    public Section sectionAt(int y) {
        int sy = Math.floorDiv(y, 16);
        // sections 数量少（overworld 24 个），线性查找足够
        for (Section s : sections) {
            if (s.y == sy) {
                return s;
            }
        }
        return null;
    }

    /** 最高 section 到最低 section 遍历，供 maxY 使用。 */
    public Section[] sectionsTopDown() {
        Section[] copy = new Section[sections.length];
        for (int i = 0; i < sections.length; i++) {
            copy[i] = sections[sections.length - 1 - i];
        }
        return copy;
    }
}
