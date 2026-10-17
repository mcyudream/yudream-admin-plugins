package online.yudream.plugin.voxelithmap.infrastructure.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎诊断解析测试。
 *
 * <p>这里喂的是 {@code RenderMapCli} 与 {@code EntityInjector} 的<b>真实输出行</b>（含缩进与百分号
 * 格式），因为这层逻辑对着的是外部进程的文本协议：引擎小版本一升级就可能静默失效，而失效的表现是
 * 「管理页上什么都不显示」——不报错、也没人发现。</p>
 */
class VoxelithDiagnosticsTest {

    /** 版本体检警告：引擎打印的是一整块（标题/详情/后果/处理），前面还有一行空行。 */
    private static final List<String> VERSION_WARNING = List.of(
            "",
            "!!! 警告：采集版本与存档版本不一致",
            "    存档是 1.21.1，而采集（models.json.gz）用的是 1.20.1",
            "    后果：贴图缺失的方块会渲染成品红色兜底；模型对应不上的方块会变成空洞。",
            "    处理：把 -PmcVersion=1.21.1（网页渲染改 render.mc-version）与对应版本的 client jar 一起用，"
                    + "并删掉 work/models.json.gz 重新采集");

    private static VoxelithDiagnostics feed(List<String> lines) {
        VoxelithDiagnostics diagnostics = new VoxelithDiagnostics();
        lines.forEach(diagnostics::accept);
        return diagnostics;
    }

    @Test
    void 版本体检警告保留标题详情与处理() {
        VoxelithDiagnostics diagnostics = feed(VERSION_WARNING);
        assertEquals(List.of(
                "!!! 警告：采集版本与存档版本不一致",
                "存档是 1.21.1，而采集（models.json.gz）用的是 1.20.1",
                "处理：把 -PmcVersion=1.21.1（网页渲染改 render.mc-version）与对应版本的 client jar 一起用，"
                        + "并删掉 work/models.json.gz 重新采集"), diagnostics.lines(),
                "「后果」是科普行，不该占诊断位");
    }

    @Test
    void 采集表头压成一行并带判据() {
        VoxelithDiagnostics diagnostics = feed(List.of(
                "首次渲染：先采集模型几何（mc=1.21.1 [资源包文件名] loader=0.16.14 mods=0，约 1 分钟）"));
        assertEquals(List.of("采集版本：1.21.1（判据：资源包文件名）"), diagnostics.lines());
    }

    @Test
    void 模型来源与实体地图画统计被留下() {
        VoxelithDiagnostics diagnostics = feed(List.of(
                "模型来源: 采集缓存 C:\\data\\models\\1.21.1-3f9c1a02\\models.json.gz（命中缓存，跳过采集）",
                "实体几何：盔甲架 3 个（简化盒体，含小型/手臂/底座与朝向；装备：盔甲 2 件、手持物 1 件），已补入瓦片几何",
                "  装备缺贴图跳过：盔甲 1 件、手持物 0 件（资源包里找不到对应贴图）",
                "地图画：展示框 5 个，地图 3 张（读到颜色 2 张），已补入瓦片几何",
                "  其中 1 个展示框没有可用地图数据（data/map_*.dat 缺失或未识别），已按原版观感画出空框"));
        assertEquals(List.of(
                "模型来源: 采集缓存 C:\\data\\models\\1.21.1-3f9c1a02\\models.json.gz（命中缓存，跳过采集）",
                "实体几何：盔甲架 3 个（简化盒体，含小型/手臂/底座与朝向；装备：盔甲 2 件、手持物 1 件），已补入瓦片几何",
                "装备缺贴图跳过：盔甲 1 件、手持物 0 件（资源包里找不到对应贴图）",
                "地图画：展示框 5 个，地图 3 张（读到颜色 2 张），已补入瓦片几何",
                "其中 1 个展示框没有可用地图数据（data/map_*.dat 缺失或未识别），已按原版观感画出空框"),
                diagnostics.lines());
    }

    /** 引擎贴图改名兜底的真实输出：表头 + 最多 20 行样例（超过 20 才补「另有」）。 */
    private static List<String> substitutions(int total, int listed) {
        List<String> raw = new ArrayList<>();
        raw.add("跨版本贴图改名兜底 " + total + " 种（已自动用同义贴图代替，不再是品红；建议仍对齐版本）:");
        for (int index = 0; index < listed; index++) {
            raw.add(String.format("    minecraft:block/alias_%02d                  → minecraft:block/real_%02d",
                    index, index));
        }
        if (total > 20) {
            raw.add("    …… 另有 " + (total - 20) + " 种");
        }
        return raw;
    }

    @Test
    void 贴图改名兜底只列有限样例并按总数概括其余() {
        // 6 种：引擎不打印「另有」行（它只在 >20 种时才补），所以概括行必须我们自己按表头总数算出来
        VoxelithDiagnostics diagnostics = feed(substitutions(6, 6));
        List<String> lines = diagnostics.lines();
        assertEquals(6, lines.size(), "1 行表头 + 4 行样例 + 1 行概括：" + lines);
        assertEquals("  - minecraft:block/alias_00 → minecraft:block/real_00", lines.get(1),
                "样例行应对齐箭头并加前缀");
        assertTrue(lines.get(5).contains("另有 2 种"), "6 种只展示 4 种，差额要讲清楚：" + lines.get(5));
    }

    @Test
    void 贴图改名兜底超过二十种时不与引擎的概括行重复() {
        // 23 种：引擎列 20 行 + 自己一行「另有 3 种」；我们的上限更低，只该出现一行概括
        VoxelithDiagnostics diagnostics = feed(substitutions(23, 20));
        List<String> lines = diagnostics.lines();
        assertEquals(6, lines.size(), "1 行表头 + 4 行样例 + 1 行概括：" + lines);
        assertTrue(lines.get(5).contains("另有 19 种"), "按表头总数 23 减掉展示的 4 种：" + lines.get(5));
        assertEquals(1, lines.stream().filter(one -> one.contains("另有")).count(),
                "不该出现两行「另有」：" + lines);
    }

    @Test
    void 贴图改名兜底总数析不出来时不乱报差额() {
        // 表头协议变了（数不出总数）：只退化到「列前几条、不概括」，宁可不说话也别报错
        List<String> raw = new ArrayList<>();
        raw.add("跨版本贴图改名兜底 若干种（已自动用同义贴图代替，不再是品红；建议仍对齐版本）:");
        for (int index = 0; index < 8; index++) {
            raw.add(String.format("    minecraft:block/alias_%02d                  → minecraft:block/real_%02d",
                    index, index));
        }
        VoxelithDiagnostics diagnostics = feed(raw);
        List<String> lines = diagnostics.lines();
        assertEquals(5, lines.size(), "1 行表头 + 4 行样例，没有概括行：" + lines);
        assertFalse(lines.stream().anyMatch(one -> one.contains("另有")), "数不出总数就不该编差额：" + lines);
    }

    @Test
    void 无关行被忽略且条目有上限() {
        VoxelithDiagnostics diagnostics = feed(List.of(
                "扫描 region 完成",
                "bake 完成：区块 1024，方块 812345，quad 912345，耗时 12.5s",
                "tile 完成 1200/1200"));
        assertTrue(diagnostics.isEmpty(), "普通日志行不该进诊断：" + diagnostics.lines());

        VoxelithDiagnostics overflow = new VoxelithDiagnostics();
        for (int index = 0; index < 40; index++) {
            overflow.accept("模型来源: 第 " + index + " 条");
        }
        assertEquals(VoxelithDiagnostics.MAX_LINES, overflow.lines().size());
        assertFalse(overflow.lines().get(overflow.lines().size() - 1).contains("第 39"),
                "超出上限时应换成「已达上限」提示");
    }
}
