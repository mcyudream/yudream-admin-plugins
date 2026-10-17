package online.yudream.plugin.voxelithmap.infrastructure.engine;

import online.yudream.plugin.voxelithmap.domain.enumerate.RenderPhase;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染输出行解析测试。
 *
 * <p>这里用的每一行都是 {@code RenderMapCli} 真实会打印的文案（已逐条对照上游源码），
 * 而不是自造格式——这段逻辑对着外部进程的文本协议，引擎把小版本升上去把文案改一个字就
 * 会静默失效（表现为进度条永远停在 0），所以必须逐条钉住。</p>
 */
class VoxelithProgressParserTest {

    private static RenderPhase phaseOf(String line) {
        Optional<VoxelithProgressParser.StageEvent> event = VoxelithProgressParser.parse(line);
        assertTrue(event.isPresent(), "应能识别：" + line);
        return event.get().phase();
    }

    private static int percentOf(String line) {
        return VoxelithProgressParser.parse(line).orElseThrow().percent();
    }

    @Test
    void 普通日志行不产生进度() {
        assertEquals(Optional.empty(), VoxelithProgressParser.parse("正在读取 level.dat"));
        assertEquals(Optional.empty(), VoxelithProgressParser.parse("资源包（低→高优先级）:"));
        assertEquals(Optional.empty(), VoxelithProgressParser.parse(""));
        assertEquals(Optional.empty(), VoxelithProgressParser.parse(null));
    }

    @Test
    void 扫描区块行推进到8且不被非空区块误判() {
        assertEquals(RenderPhase.SCAN, phaseOf("  扫描区块 12/345，已找到非空 8"));
        assertEquals(8, percentOf("  扫描区块 12/345，已找到非空 8"));
    }

    @Test
    void 采集与模型来源分别给3和10() {
        assertEquals(RenderPhase.HARVEST, phaseOf("首次渲染：先采集模型几何（mc=1.20.1 loader=0.16.14 mods=0，约 1 分钟）"));
        assertEquals(3, percentOf("首次渲染：先采集模型几何（mc=1.20.1 loader=0.16.14 mods=0，约 1 分钟）"));
        assertEquals(RenderPhase.HARVEST, phaseOf("模型来源: 采集缓存 /work/models.json.gz（命中缓存，跳过采集）"));
        assertEquals(10, percentOf("模型来源: 静态解析（skipHarvest；uvlock/旋转等处可能与原版有偏差）"));
    }

    @Test
    void 维度规模行推进到15并带出非空区块数() {
        String line = "维度 minecraft:overworld：窗口 3 个 region，非空区块 12345";
        assertEquals(RenderPhase.SCAN, phaseOf(line));
        assertEquals(15, percentOf(line));
        assertEquals(Optional.of(12345L), VoxelithProgressParser.nonEmptyChunks(line));
        assertEquals(Optional.empty(), VoxelithProgressParser.nonEmptyChunks("没有数字的行"));
    }

    @Test
    void 多遍渲染规划与批次行() {
        String plan = "多遍渲染：窗口 1234 个非空区块 → 47 批（每批约 26 区块、按 region 对齐）";
        assertEquals(RenderPhase.BAKE, phaseOf(plan));
        assertEquals(12, percentOf(plan), "规划行应在批次行之前，否则会被回跳");

        // 批次百分比与上游 stageOf 同式：15 + 79*(i-1)/N
        assertEquals(15, percentOf("多遍渲染 批次 1/47：本批 26 区块（累计 26/1234）"));
        assertEquals(18, percentOf("多遍渲染 批次 3/47：本批 26 区块（累计 78/1234）"));
        assertEquals(92, percentOf("多遍渲染 批次 47/47：本批 20 区块（累计 1234/1234）"));
    }

    @Test
    void 多遍批次序列自身单调且不超过100() {
        int previous = 0;
        for (int i = 1; i <= 47; i++) {
            int percent = percentOf("多遍渲染 批次 " + i + "/47：本批 26 区块");
            assertTrue(percent >= previous, "批次进度不能回跳：" + i + "/47 → " + percent);
            assertTrue(percent <= 100, "批次百分比不应超过 100：" + percent);
            previous = percent;
        }
    }

    @Test
    void 单遍各阶段完成文案() {
        assertEquals(14, percentOf("共享图集已就绪"));
        assertEquals(60, percentOf("bake 完成：区块 26，方块 12345，quad 67890，耗时 1.2s"));
        assertEquals(78, percentOf("tile 完成：hires 瓦片 26，图集 16² / 256 格，耗时 0.8s"));
        assertEquals(92, percentOf("lod 完成：LOD 瓦片 3，层级 3，耗时 0.1s"));
    }

    @Test
    void 带计数的兼容形态仍按比例推进() {
        // 上游当前只打印阶段完成行，不打印计数行；这条正则是给「带计数的变体」留的后路
        assertEquals(85, percentOf("tile 完成 tiles 50/100"));
        assertEquals(92, percentOf("tile 完成 tiles 100/100"));
        assertEquals(94, percentOf("lod 完成 lod 5/10"));
    }

    @Test
    void 计数行携带瓦片计数供任务进度使用() {
        // 前端「n/m」的来源：计数行必须把 done/total 带出 StageEvent，
        // 否则 RenderTask.reportTiles 无人可调，任务进度永远显示 0/0
        VoxelithProgressParser.StageEvent tiles =
                VoxelithProgressParser.parse("tile 完成 tiles 50/100").orElseThrow();
        assertEquals(50, tiles.tilesDone());
        assertEquals(100, tiles.tilesTotal());
        VoxelithProgressParser.StageEvent lod =
                VoxelithProgressParser.parse("lod 完成 lod 5/10").orElseThrow();
        assertEquals(5, lod.tilesDone());
        assertEquals(10, lod.tilesTotal());
        // 非计数行不携带计数（null），编排层据此跳过 reportTiles
        VoxelithProgressParser.StageEvent plain =
                VoxelithProgressParser.parse("bake 完成").orElseThrow();
        assertEquals(null, plain.tilesDone());
        assertEquals(null, plain.tilesTotal());
    }

    @Test
    void 渲染完成是终点() {
        assertEquals(RenderPhase.DONE, phaseOf("=== 渲染完成 ==="));
        assertEquals(100, percentOf("=== 渲染完成 ==="));
    }

    @Test
    void 单遍模式全序列单调() {
        String[] sequence = {
                "  扫描区块 1/345，已找到非空 1",
                "维度 minecraft:overworld：窗口 3 个 region，非空区块 12345",
                "bake 完成：区块 26，方块 12345，quad 67890，耗时 1.2s",
                "tile 完成：hires 瓦片 26，图集 16² / 256 格，耗时 0.8s",
                "lod 完成：LOD 瓦片 3，层级 3，耗时 0.1s",
                "=== 渲染完成 ===",
        };
        int previous = 0;
        for (String line : sequence) {
            int percent = percentOf(line);
            assertTrue(percent >= previous, "单遍进度回跳：" + line + " → " + percent);
            previous = percent;
        }
        assertEquals(100, previous);
    }
}
