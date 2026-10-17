package online.yudream.plugin.voxelithmap.infrastructure.engine;

import online.yudream.plugin.voxelithmap.domain.enumerate.RenderPhase;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voxelith 渲染子进程输出行的解析。
 *
 * <p>纯函数，便于单测——这段逻辑对着的是外部进程的文本协议，最容易在引擎小版本升级后
 * 静默失效，必须有测试钉住。</p>
 *
 * <p>关键字口径与 Voxelith 自己的 {@code RenderJobService.stageOf} 一致：
 * {@code 扫描区块}→8、{@code 先采集模型几何}→3、{@code 模型来源}→10、{@code 非空区块}→15、
 * {@code bake 完成}→60、{@code tile 完成}→78、{@code lod 完成}→92、{@code === 渲染完成 ===}→100。
 * 另外识别多遍渲染的批次行，把 15→94 这段摊到每一批上。所有按比例推进的分支一律
 * 用整数向下取整（{@code span * done / total}），保证百分比既单调又不会越过本阶段上界。</p>
 */
public final class VoxelithProgressParser {

    /** 非空区块计数：{@code 非空区块 12345} 或 {@code 非空区块: 12345}。 */
    private static final Pattern NON_EMPTY_CHUNKS =
            Pattern.compile("非空区块\\s*[:：]?\\s*(\\d+)");
    /** 多遍渲染批次：{@code 多遍渲染 批次 3/47}。 */
    private static final Pattern BATCH = Pattern.compile("多遍渲染\\s*批次\\s*(\\d+)\\s*/\\s*(\\d+)");
    /** 瓦片计数：{@code tile 完成 1234/5000} 或 {@code tiles 1234/5000}。 */
    private static final Pattern TILE_COUNTS =
            Pattern.compile("(?i)tiles?\\s*(\\d+)\\s*/\\s*(\\d+)");
    /** LOD 计数：{@code lod 完成 12/30}。 */
    private static final Pattern LOD_COUNTS =
            Pattern.compile("(?i)lod\\s*(\\d+)\\s*/\\s*(\\d+)");
    /** 阶段起始时的进度下限（与 Voxelith 的 stageOf 同值）。 */
    private static final int BATCH_BASE = 15;
    private static final int BATCH_CEILING = 94;
    /** 计数型兼容行：tile 78→92，lod 92→97，与阶段完成行的定值对齐。 */
    private static final int TILE_BASE = 78;
    private static final int TILE_SPAN = 14;
    private static final int LOD_BASE = 92;
    private static final int LOD_SPAN = 5;

    private VoxelithProgressParser() {
    }

    /**
     * 解析一行输出。
     *
     * @param line 子进程 stdout 的一行
     * @return 有意义的进度事件；普通日志行返回空
     */
    public static Optional<StageEvent> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        Matcher batch = BATCH.matcher(line);
        if (batch.find()) {
            int index = Integer.parseInt(batch.group(1));
            int total = Math.max(1, Integer.parseInt(batch.group(2)));
            // 与上游 RenderJobService.stageOf 同式：第 i 批「开始时」给 15，逐批推进至多到 94；
            // 收尾由「=== 渲染完成 ===」补到 100。多遍模式下只有批次行会连续出现，
            // 因此这条序列必须自身单调——否则进度条会来回跳。
            int percent = BATCH_BASE + Math.min(BATCH_CEILING - BATCH_BASE,
                    (BATCH_CEILING - BATCH_BASE) * (index - 1) / total);
            return Optional.of(new StageEvent(RenderPhase.BAKE, percent,
                    "多遍渲染 " + index + "/" + total + " 批", null, null));
        }
        if (line.contains("=== 渲染完成 ===")) {
            return Optional.of(new StageEvent(RenderPhase.DONE, 100, "渲染完成", null, null));
        }
        Matcher tiles = TILE_COUNTS.matcher(line);
        if (tiles.find() && line.contains("完成")) {
            int done = Integer.parseInt(tiles.group(1));
            int total = Math.max(1, Integer.parseInt(tiles.group(2)));
            int percent = TILE_BASE + (int) ((long) TILE_SPAN * done / total);
            return Optional.of(new StageEvent(RenderPhase.TILE, percent, "瓦片 " + done + "/" + total,
                    done, total));
        }
        Matcher lod = LOD_COUNTS.matcher(line);
        if (lod.find() && line.contains("完成")) {
            int done = Integer.parseInt(lod.group(1));
            int total = Math.max(1, Integer.parseInt(lod.group(2)));
            int percent = LOD_BASE + (int) ((long) LOD_SPAN * done / total);
            return Optional.of(new StageEvent(RenderPhase.LOD, percent, "LOD " + done + "/" + total,
                    done, total));
        }
        if (line.contains("lod 完成")) {
            return Optional.of(new StageEvent(RenderPhase.LOD, 92, "LOD 完成", null, null));
        }
        if (line.contains("tile 完成")) {
            return Optional.of(new StageEvent(RenderPhase.TILE, 78, "瓦片完成", null, null));
        }
        if (line.contains("bake 完成")) {
            return Optional.of(new StageEvent(RenderPhase.BAKE, 60, "几何烘焙完成", null, null));
        }
        if (line.contains("共享图集已就绪")) {
            return Optional.of(new StageEvent(RenderPhase.BAKE, 14, "共享图集就绪", null, null));
        }
        if (line.contains("多遍渲染：窗口")) {
            return Optional.of(new StageEvent(RenderPhase.BAKE, 12, "准备多遍渲染", null, null));
        }
        Matcher chunks = NON_EMPTY_CHUNKS.matcher(line);
        if (chunks.find()) {
            long count = Long.parseLong(chunks.group(1));
            return Optional.of(new StageEvent(RenderPhase.SCAN, 15, "非空区块 " + count, null, null));
        }
        if (line.contains("模型来源")) {
            return Optional.of(new StageEvent(RenderPhase.HARVEST, 10, "确定模型来源", null, null));
        }
        if (line.contains("先采集模型几何")) {
            return Optional.of(new StageEvent(RenderPhase.HARVEST, 3, "采集模型几何", null, null));
        }
        if (line.contains("扫描区块") || line.contains("扫描 region")) {
            return Optional.of(new StageEvent(RenderPhase.SCAN, 8, "扫描区块", null, null));
        }
        return Optional.empty();
    }

    /** 单独取非空区块数（用于「超单次上限」的提示里给出实际规模）。 */
    public static Optional<Long> nonEmptyChunks(String line) {
        Matcher matcher = NON_EMPTY_CHUNKS.matcher(line == null ? "" : line);
        return matcher.find() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }

    /**
     * 一次有意义的进度推进。
     *
     * @param phase      阶段
     * @param percent    该阶段下的整体百分比
     * @param message    展示消息
     * @param tilesDone  行内携带的已完成瓦片计数；该行不是计数行时为 {@code null}
     * @param tilesTotal 行内携带的瓦片总数；该行不是计数行时为 {@code null}
     */
    public record StageEvent(RenderPhase phase, int percent, String message,
                             Integer tilesDone, Integer tilesTotal) {
    }
}
