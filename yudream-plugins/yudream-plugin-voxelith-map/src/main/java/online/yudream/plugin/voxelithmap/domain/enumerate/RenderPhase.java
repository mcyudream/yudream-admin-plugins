package online.yudream.plugin.voxelithmap.domain.enumerate;

import java.util.Locale;

/**
 * 渲染阶段与进度权重。
 *
 * <p>权重取自 Voxelith 渲染管线的实际耗时占比（bake 最重、tile 次之），
 * 用单调递增的权重把「阶段跳变」摊平成一条可读的进度条，而不是 0%→100% 的突变。</p>
 *
 * <p>阶段识别靠解析子进程 stdout 的关键行（{@link #of(String)}）。Voxelith 的
 * {@code RenderJobService.stageOf} 用的是同一套关键字，这里保持口径一致，避免
 * 「服务端显示烘焙中、插件显示扫描中」这种对不上的情况。</p>
 */
public enum RenderPhase {
    QUEUED(0, "排队中"),
    SCAN(8, "扫描区块"),
    HARVEST(12, "采集模型"),
    BAKE(60, "烘焙几何"),
    TILE(78, "生成瓦片"),
    LOD(92, "生成 LOD"),
    MANIFEST(97, "写入清单"),
    IMPORT(99, "导入产物"),
    DONE(100, "已完成");

    private final int weight;
    private final String label;

    RenderPhase(int weight, String label) {
        this.weight = weight;
        this.label = label;
    }

    /** 完成到该阶段时的整体百分比。 */
    public int weight() {
        return weight;
    }

    /** 中文展示名。 */
    public String label() {
        return label;
    }

    /**
     * 从一个阶段序号解析阶段（用于「阶段只会前进」的收敛）。
     *
     * @throws IllegalArgumentException 序号越界
     */
    public static RenderPhase byOrdinal(int ordinal) {
        RenderPhase[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("阶段序号越界: " + ordinal);
        }
        return values[ordinal];
    }

    /**
     * 按子进程输出行识别阶段。识别不出（普通日志行）返回 {@code null}，
     * 调用方保持当前阶段不变——不能因为一行普通日志把进度打回起点。
     */
    public static RenderPhase of(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String text = line.toLowerCase(Locale.ROOT);
        if (line.contains("=== 渲染完成 ===")) {
            return DONE;
        }
        if (line.contains("manifest") || line.contains("清单")) {
            return MANIFEST;
        }
        if (line.contains("lod 完成") || text.contains("lod")) {
            return LOD;
        }
        if (line.contains("tile 完成") || text.contains("tile")) {
            return TILE;
        }
        if (line.contains("bake") || line.contains("烘焙")) {
            return BAKE;
        }
        if (line.contains("采集") || line.contains("模型来源") || text.contains("harvest")) {
            return HARVEST;
        }
        if (line.contains("非空区块") || line.contains("扫描")) {
            return SCAN;
        }
        return null;
    }
}
