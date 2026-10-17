package online.yudream.plugin.voxelithmap.infrastructure.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 渲染子进程输出里「关键诊断」的收集器。
 *
 * <p>为什么必须结构化留下：引擎把最有价值的两类信息都打在最<b>前面</b>——版本体检警告
 * （存档/资源包/采集版本不一致 → 整片品红或空洞）与「模型来源」（命中采集缓存 / 静态解析 /
 * 指定产物）。而任务只保留日志的<b>尾部</b> 4000 字，等渲染跑完，这些行早被几千行瓦片日志挤掉，
 * 用户翻不到、也就不会去修。</p>
 *
 * <p>口径对着的是外部进程的文本协议，引擎小版本升级就可能静默失效，所以：只认前缀不认整句、
 * 认不出就跳过、每条诊断都保留引擎原文（不做二次转述）。</p>
 */
public final class VoxelithDiagnostics {

    /** 收集上限：管理页一屏之内，且要跟任务文档一起落库。 */
    public static final int MAX_LINES = 24;
    /** 单行上限：有的行很长（带绝对路径），超长截断而不是整条丢弃。 */
    private static final int MAX_LINE_CHARS = 400;
    /** 贴图改名兜底最多列几行样例（其余用「另有 N 种」概括）。 */
    private static final int MAX_SUBSTITUTION_SAMPLES = 4;
    /** 诊断条目被截断时补的说明。 */
    private static final String TRUNCATED_NOTE = "……（诊断条目已达上限，更多细节见任务日志）";

    private static final String WARNING_PREFIX = "!!! 警告：";
    private static final String WARNING_TAIL_SKIP = "后果：";
    private static final String WARNING_TAIL_KEEP = "处理：";
    private static final String HARVEST_HEADER_PREFIX = "首次渲染：先采集模型几何";
    private static final String MODEL_SOURCE_PREFIX = "模型来源";
    private static final String HARVEST_EMPTY_PREFIX = "采集未产出";
    private static final String ENTITY_PREFIX = "实体几何";
    private static final String ENTITY_SKIP_PREFIX = "装备缺贴图跳过";
    private static final String MAP_ART_PREFIX = "地图画：";
    private static final String MAP_ART_EMPTY_PREFIX = "其中";
    private static final String SUBSTITUTION_PREFIX = "跨版本贴图改名兜底";
    private static final char SUBSTITUTION_ARROW_CHAR = '\u2192';

    /** 采集表头里的 {@code mc=1.21.1 [资源包文件名]}。 */
    private static final Pattern HARVEST_HEADER = Pattern.compile("mc=([^\\s\\[]+)\\s*\\[([^]]+)]");
    /** 贴图别名样例行的 {@code a → b} 对齐空格。 */
    private static final Pattern SUBSTITUTION_ARROW = Pattern.compile("\\s*→\\s*");
    /** 贴图改名兜底表头声明的总种数：{@code 跨版本贴图改名兜底 6 种（…）:}。 */
    private static final Pattern SUBSTITUTION_COUNT = Pattern.compile("兜底\\s*(\\d+)\\s*种");

    private final List<String> lines = new ArrayList<>();
    private boolean inWarningTail;
    private boolean inMapArtTail;
    /** 贴图改名兜底块：还能再列几条样例（列满即用一行概括其余）。 */
    private int substitutionPending;
    /** 贴图改名兜底块：按表头总数算出的、没能展示出来的种数。 */
    private int substitutionOmitted;
    private boolean truncated;

    /** 消费一行子进程输出；不关心的行会被忽略。 */
    public void accept(String rawLine) {
        if (rawLine == null) {
            return;
        }
        String line = rawLine.strip();
        if (line.isEmpty()) {
            inWarningTail = false;
            inMapArtTail = false;
            substitutionPending = 0;
            substitutionOmitted = 0;
            return;
        }
        // 版本体检警告是多行块：标题 / 详情 / 后果 / 处理。只留详情与处理——
        // 「后果」是科普，管理页上每天看一遍没意义
        if (line.startsWith(WARNING_PREFIX)) {
            inWarningTail = true;
            append(line);
            return;
        }
        if (inWarningTail) {
            if (line.startsWith(WARNING_TAIL_SKIP)) {
                return;
            }
            if (line.startsWith(WARNING_TAIL_KEEP)) {
                append(line);
                inWarningTail = false;
                return;
            }
            append(line);
            return;
        }
        if (line.startsWith(HARVEST_HEADER_PREFIX)) {
            append(describeHarvestHeader(line));
            return;
        }
        if (line.startsWith(MODEL_SOURCE_PREFIX) || line.startsWith(HARVEST_EMPTY_PREFIX)
                || line.startsWith(ENTITY_PREFIX) || line.startsWith(ENTITY_SKIP_PREFIX)) {
            append(line);
            return;
        }
        if (line.startsWith(MAP_ART_PREFIX)) {
            append(line);
            inMapArtTail = true;
            return;
        }
        if (inMapArtTail && line.startsWith(MAP_ART_EMPTY_PREFIX)) {
            append(line);
            inMapArtTail = false;
            return;
        }
        inMapArtTail = false;
        if (line.startsWith(SUBSTITUTION_PREFIX)) {
            append(line);
            // 引擎自己只在超过 20 种时才补一行「…… 另有 N 种」（见 RenderMapCli#reportTextureSubstitutions），
            // 而管理页的上限低得多。所以不能等引擎的概括行，直接按表头声明的总数自己算差额，
            // 否则 5~20 种这一段会被静默吃掉、用户以为「只有 4 种」。
            int total = substitutionCount(line);
            substitutionPending = total > 0 ? Math.min(total, MAX_SUBSTITUTION_SAMPLES) : MAX_SUBSTITUTION_SAMPLES;
            substitutionOmitted = total > MAX_SUBSTITUTION_SAMPLES ? total - MAX_SUBSTITUTION_SAMPLES : 0;
            return;
        }
        // 样例行的判据是箭头：这样即使表头之后混进别的行，也不会被误当成别名样例。
        if (substitutionPending > 0 && line.indexOf(SUBSTITUTION_ARROW_CHAR) >= 0) {
            append("  - " + SUBSTITUTION_ARROW.matcher(line).replaceAll(" → "));
            if (--substitutionPending == 0) {
                appendOmitted();
            }
        }
    }

    /** 当前诊断快照（按出现顺序）。 */
    public List<String> lines() {
        if (!truncated) {
            return List.copyOf(lines);
        }
        List<String> result = new ArrayList<>(lines);
        result.set(result.size() - 1, TRUNCATED_NOTE);
        return List.copyOf(result);
    }

    /** 是否收集到了任何诊断。 */
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * 把采集表头压成一行可读的诊断。
     *
     * <p>原文：{@code 首次渲染：先采集模型几何（mc=1.21.1 [资源包文件名] loader=0.16.14 mods=0，约 1 分钟）}；
     * 这里只留「用了哪个版本、判据是什么」——用户最需要确认的就是这两点。</p>
     */
    private static String describeHarvestHeader(String line) {
        Matcher matcher = HARVEST_HEADER.matcher(line);
        if (!matcher.find()) {
            return line;
        }
        return "采集版本：" + matcher.group(1) + "（判据：" + matcher.group(2) + "）";
    }

    /** 样例列满后补一行「还有多少种没展示」；表头数不出来（协议变了）时保持沉默。 */
    private void appendOmitted() {
        if (substitutionOmitted > 0) {
            append("  …… 另有 " + substitutionOmitted + " 种（此处仅展示前 " + MAX_SUBSTITUTION_SAMPLES + " 种）");
        }
    }

    /** 从表头解析总种数；解析不到返回 0（调用方退化成「只列样例、不做概括」）。 */
    private static int substitutionCount(String line) {
        Matcher matcher = SUBSTITUTION_COUNT.matcher(line);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private void append(String line) {
        if (lines.size() >= MAX_LINES) {
            truncated = true;
            return;
        }
        lines.add(line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "…" : line);
    }
}
