package online.yudream.plugin.voxelithmap.domain.value;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 存档维度与「实例里的存档目录」的对应关系。
 *
 * <p>Minecraft 的存档按维度分目录：{@code world}（主世界）、{@code world_nether}（下界）、
 * {@code world_the_end}（末地），旧版服务端还会用 {@code DIM-1}/{@code DIM1}。每个维度各自对应
 * 一张地图——渲染、封面、瓦片都是按维度独立的。</p>
 *
 * <p>实例导入据此：列出实例里的这些目录 → 一个目录建一张地图，地图的 {@code worldName} 记成该目录名，
 * 渲染时就能从同一个存档包里定位到对应维度的存档根（见 {@code WorldArchiveExtractor}）。</p>
 */
public record InstanceDimensionDir(String dirName, String dimension, String packPath) {

    /** 打包路径缺省与存档根目录同名（新版布局：维度目录本身就是一份完整存档）。 */
    public InstanceDimensionDir {
        packPath = packPath == null || packPath.isBlank() ? dirName : packPath;
    }

    /**
     * 老版布局：维度数据在 {@code world/DIM-1}、{@code world/DIM1} 里。
     *
     * <p>{@code dirName} 仍是**新版目录名**（{@code world_nether}/{@code world_the_end}）：它是归一后的
     * 存档根名，地图的 {@code worldName} 也用它；{@code packPath} 指向真正要打包的父目录
     * （{@code world}，因为维度数据连同 level.dat 都在里面）。</p>
     *
     * @param parentDir      维度数据所在的父目录（通常就是 {@code world}）
     * @param nestedDirName  父目录里的维度子目录名（{@code DIM-1}/{@code DIM1}）
     * @return 识别不出维度时返回空
     */
    public static Optional<InstanceDimensionDir> legacyNested(String parentDir, String nestedDirName) {
        if (parentDir == null || parentDir.isBlank() || !isLegacyNestedDir(nestedDirName)) {
            return Optional.empty();
        }
        return dimensionOf(nestedDirName)
                .map(dimension -> new InstanceDimensionDir(dirFor(dimension), dimension, parentDir));
    }

    /** 主世界目录（默认存档根）。 */
    public static final String OVERWORLD_DIR = "world";
    /** 下界目录。 */
    public static final String NETHER_DIR = "world_nether";
    /** 末地目录。 */
    public static final String END_DIR = "world_the_end";

    /**
     * 老版存档把维度塞在 {@code world} 里面的目录名：{@code world/DIM-1} 是下界、{@code world/DIM1} 是末地。
     *
     * <p>这些子目录**没有 level.dat**，与新版同级目录（{@code world_nether}/{@code world_the_end}，
     * 各自带 level.dat）是两种不同布局；导入与解包都要认（见 {@code WorldArchiveExtractor}）。</p>
     */
    public static final String LEGACY_NETHER_DIR = "DIM-1";
    /** 老版末地目录名（见 {@link #LEGACY_NETHER_DIR}）。 */
    public static final String LEGACY_END_DIR = "DIM1";

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:nether";
    public static final String THE_END = "minecraft:the_end";

    /** 维度 → 目录（新建/更新地图时由维度推导存档目录名）。 */
    public static String dirFor(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return OVERWORLD_DIR;
        }
        return switch (dimension.trim().toLowerCase(Locale.ROOT)) {
            case NETHER, "nether" -> NETHER_DIR;
            case THE_END, "the_end", "end" -> END_DIR;
            default -> OVERWORLD_DIR;
        };
    }

    /** 目录名 → 维度；不认识的目录返回空（不是维度目录，导入时跳过）。 */
    public static Optional<String> dimensionOf(String dirName) {
        if (dirName == null || dirName.isBlank()) {
            return Optional.empty();
        }
        String name = dirName.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return switch (name) {
            case OVERWORLD_DIR, "world_overworld" -> Optional.of(OVERWORLD);
            case NETHER_DIR, "dim-1", "dim_1", "world_nether_" -> Optional.of(NETHER);
            case END_DIR, "dim1", "dim_1_end" -> Optional.of(THE_END);
            default -> Optional.empty();
        };
    }

    /**
     * 维度对应的**老版**嵌套目录名（{@code world/DIM-1} / {@code world/DIM1}）；不是下界/末地返回 null。
     */
    public static String legacyNestedDirName(String dimension) {
        String dir = dirFor(dimension);
        if (NETHER_DIR.equals(dir)) {
            return LEGACY_NETHER_DIR;
        }
        if (END_DIR.equals(dir)) {
            return LEGACY_END_DIR;
        }
        return null;
    }

    /** 这个目录名是不是老版嵌套维度目录（{@code DIM-1}/{@code DIM1}，忽略大小写）。 */
    public static boolean isLegacyNestedDir(String dirName) {
        if (dirName == null || dirName.isBlank()) {
            return false;
        }
        String name = dirName.trim();
        return LEGACY_NETHER_DIR.equalsIgnoreCase(name) || LEGACY_END_DIR.equalsIgnoreCase(name);
    }

    /**
     * 从一组实例根目录的条目里挑出维度目录（顺序固定：主世界 → 下界 → 末地）。
     *
     * <p>{@code dirName} 保留实例里**真实**的目录名（旧版服务端的 {@code DIM-1}/{@code DIM1} 也认）：
     * 打包要按真实路径走，地图的 {@code worldName} 也得与包内目录名一致，渲染时才能定位到存档根。</p>
     *
     * @param entries 目录条目名（非目录会被忽略）
     */
    public static List<InstanceDimensionDir> pick(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<InstanceDimensionDir> found = new java.util.ArrayList<>();
        for (String dimension : List.of(OVERWORLD, NETHER, THE_END)) {
            entries.stream()
                    .filter(entry -> entry != null && !entry.isBlank())
                    .filter(entry -> dimensionOf(entry).filter(dimension::equals).isPresent())
                    .findFirst()
                    .ifPresent(entry -> found.add(new InstanceDimensionDir(normalizeDir(entry), dimension, null)));
        }
        return List.copyOf(found);
    }

    /** 归一条目名：去空白、统一分隔符、去掉开头的 {@code ./}（面板个别节点会带）。 */
    private static String normalizeDir(String entry) {
        String name = entry.trim().replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        return name;
    }

    /** 维度中文名（任务日志与地图名后缀用）。 */
    public static String label(String dimension) {
        if (dimension == null) {
            return "主世界";
        }
        return switch (dimension.trim().toLowerCase(Locale.ROOT)) {
            case NETHER, "nether" -> "下界";
            case THE_END, "the_end", "end" -> "末地";
            default -> "主世界";
        };
    }

    /**
     * 维度的展示顺序权重：主世界 → 下界 → 末地 → 其它。
     *
     * <p>维度切换按钮的顺序必须稳定（同一个世界的三个维度每次打开都在同一位置），
     * 因此排序用这个固定权重，而不是按名称或更新时间。</p>
     */
    public static int dimensionRank(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return 0;
        }
        return switch (dimension.trim().toLowerCase(Locale.ROOT)) {
            case OVERWORLD, "overworld" -> 0;
            case NETHER, "nether" -> 1;
            case THE_END, "the_end", "end" -> 2;
            default -> 3;
        };
    }
}
