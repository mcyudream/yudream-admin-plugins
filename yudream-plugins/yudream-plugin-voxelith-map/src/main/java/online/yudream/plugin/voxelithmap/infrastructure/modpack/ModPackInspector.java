package online.yudream.plugin.voxelithmap.infrastructure.modpack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组包完整性排查：把「导入的模组集合是否自洽」在导入阶段就查出来。
 *
 * <p>起因是一次真实渲染：模组包少了 {@code cristellib}，Fabric 引导直接拒绝启动
 * （{@code t_and_t requires any version of cristellib, which is missing}），采集退回静态解析——
 * 渲染仍能出图，但模组方块大面积缺几何，而这条线索只出现在渲染日志的深处。导入时若能立刻指出
 * 「哪个模组依赖哪个缺失的模组」，补包就是一次明确的操作，而不是等渲染完去猜。</p>
 *
 * <p>只做只读排查，<b>绝不改动模组集合</b>：缺依赖是实例自身的问题，插件只负责如实报告。
 * 依赖来自各模组自带的元数据：Fabric 读 {@code fabric.mod.json}（{@code id}/{@code provides}/{@code depends}），
 * Forge/NeoForge 读 {@code META-INF/mods.toml} 与 {@code META-INF/neoforge.mods.toml}
 * （{@code modId} 与 {@code [[dependencies]]} 里的 {@code mandatory=true} 项，{@code mandatory=false} 视为可选不报）。
 * 平台自身（minecraft / 加载器 / java）不算模组依赖，不参与判定。</p>
 *
 * <p>读包用 {@link ZipFile}（走中央目录）：面板/节点写出的 zip 形态不一（个别工具会用
 * STORED + 数据描述符），流式读取会整包拒绝，中央目录读取两种形态都认。</p>
 */
public final class ModPackInspector {

    /** 平台自身的依赖 id：不算「缺失的模组」。 */
    private static final Set<String> PLATFORM_IDS = Set.of(
            "minecraft", "java", "fabricloader", "fabric-loader", "fabric", "forge", "neoforge", "javafml", "lowcodefml");

    /** mods.toml 里的键值（Forge/NeoForge 的元数据是 TOML，这里只取需要的两三个键）。 */
    private static final Pattern TOML_KEY = Pattern.compile("^\\s*([A-Za-z][\\w.-]*)\\s*=\\s*(.+?)\\s*(?:#.*)?$");
    /** 依赖块头：{@code [[dependencies.<owner>]]}。 */
    private static final Pattern TOML_DEPENDENCY_BLOCK = Pattern.compile("^\\s*\\[\\[\\s*dependencies\\s*\\.\\s*([\\w.-]+)\\s*]]\\s*$");

    private final ObjectMapper objectMapper;

    public ModPackInspector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 包内的一个模组 jar（列表展示用：名称 + 字节数，不做解压）。 */
    public record JarInfo(String name, long sizeBytes) {
    }

    /**
     * 列出模组包里的 jar 文件（**只读中央目录，不解压**）。
     *
     * <p>用于管理页「看看这个 zip 里有哪些模组」：只读中央目录意味着不必解压整包，但宿主 SPI 只给
     * 顺序流、zip 的中央目录在文件末尾，所以调用方仍需先把包物化到本地（这是一次完整对象读）——
     * 因此本接口是**按需**触发，不在页面加载时自动跑。</p>
     *
     * @param maxEntries 最多返回多少条（超出部分只报总数，避免超大包把响应撑爆）
     * @throws IOException 不是合法 zip 或读取失败
     */
    public static List<JarInfo> listJars(Path archive, int maxEntries) throws IOException {
        List<JarInfo> jars = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                jars.add(new JarInfo(name, Math.max(0, entry.getSize())));
            }
        }
        jars.sort(Comparator.comparing(JarInfo::name));
        return jars.size() > maxEntries ? List.copyOf(jars.subList(0, maxEntries)) : List.copyOf(jars);
    }

    /** 一个模组的元数据（只保留排查需要的部分）。 */
    private record ModMeta(String id, Set<String> provides, Map<String, Boolean> requires) {
    }

    /** 排查结果。 */
    public record Report(int jarCount, int modCount, List<String> unreadable, List<String> corrupt,
                         Map<String, Set<String>> missing) {

        /** 是否存在缺失依赖。 */
        public boolean hasMissing() {
            return !missing.isEmpty();
        }

        /**
         * 这个包能不能用。
         *
         * <p>判据是「有没有 jar 解压失败」：那说明包在打包或传输中被截断，渲染阶段解包必然失败
         * （{@code Unexpected end of ZLIB input stream}）。缺失依赖不影响可用性——那只是模组集合
         * 不完整，渲染会退回静态解析，用户可后续补包。</p>
         */
        public boolean usable() {
            return corrupt.isEmpty();
        }

        /** 缺失依赖的摘要（按「需要它的模组」排序）。 */
        public List<String> missingSummary() {
            List<String> lines = new ArrayList<>();
            missing.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> lines.add(entry.getKey() + " 需要 " + String.join("、", entry.getValue())));
            return List.copyOf(lines);
        }

        /** 归档里读不出来的条目（截断/损坏的包）。 */
        public List<String> unreadableEntries() {
            return List.copyOf(unreadable);
        }
    }

    /**
     * 排查一个模组包。
     *
     * @param archive 模组包 zip（调用方负责物化到本地）
     * @return 排查结果；包整体打不开时抛 {@link IOException}
     */
    public Report inspect(Path archive) throws IOException {
        List<ModMeta> mods = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        List<String> corrupt = new ArrayList<>();
        int jarCount = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                jarCount++;
                try (InputStream input = zip.getInputStream(entry)) {
                    List<ModMeta> metas = readModMeta(input);
                    if (metas.isEmpty()) {
                        // 一个正常的模组 jar 一定有元数据（Fabric 的 fabric.mod.json / Forge 的 mods.toml）：
                        // 没有就说明它不是模组（误放进 mods 的库）或包已损坏——这属于完整性排查该报的事
                        unreadable.add(name + "（无可识别模组元数据：可能不是模组，或包已损坏）");
                    }
                    else {
                        mods.addAll(metas);
                    }
                }
                catch (IOException | RuntimeException e) {
                    // 解压失败 = 包在打包/传输中被截断，渲染解包时必然同样失败：单独归类，导入端据此丢弃模组包
                    corrupt.add(name + "（" + (e.getMessage() == null || e.getMessage().isBlank()
                            ? e.getClass().getSimpleName() : e.getMessage()) + "）");
                }
            }
        }
        return new Report(jarCount, mods.size(), List.copyOf(unreadable), List.copyOf(corrupt),
                missingDependencies(mods));
    }

    /** 逐个比对：模组声明的必需依赖里，有哪些 id 在这个模组包里根本没有。 */
    private static Map<String, Set<String>> missingDependencies(List<ModMeta> mods) {
        // 这个包里「能提供什么」：每个模组自己的 id + 它声明的 provides
        Set<String> provided = new LinkedHashSet<>();
        for (ModMeta mod : mods) {
            if (!mod.id().isBlank()) {
                provided.add(normalize(mod.id()));
            }
            mod.provides().forEach(id -> provided.add(normalize(id)));
        }
        Map<String, Set<String>> missing = new LinkedHashMap<>();
        for (ModMeta mod : mods) {
            Set<String> absent = new TreeSet<>();
            mod.requires().forEach((id, mandatory) -> {
                String key = normalize(id);
                if (mandatory && !key.isBlank() && !PLATFORM_IDS.contains(key) && !provided.contains(key)) {
                    absent.add(id.trim());
                }
            });
            if (!absent.isEmpty()) {
                missing.put(mod.id().isBlank() ? "（未命名模组）" : mod.id(), Set.copyOf(absent));
            }
        }
        return Map.copyOf(missing);
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** 读一个模组 jar 里可能存在的元数据（Fabric 的 fabric.mod.json / Forge 的 mods.toml）。 */
    private List<ModMeta> readModMeta(InputStream jarStream) throws IOException {
        List<ModMeta> found = new ArrayList<>();
        // 一个 jar 可能同时带多种元数据（多加载器支持），逐个读到就用
        try (var zip = new java.util.zip.ZipInputStream(jarStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!name.equals("fabric.mod.json")
                        && !name.equals("meta-inf/mods.toml")
                        && !name.equals("meta-inf/neoforge.mods.toml")) {
                    // 非元数据条目也要读完：ZipInputStream 靠"读"来解压，条目数据被截断会在这里抛
                    // （正是渲染侧解包失败的那处伤），跳着读就查不出半截包了
                    zip.transferTo(java.io.OutputStream.nullOutputStream());
                    continue;
                }
                // 先把这一条读完再解析：Jackson 默认会关掉它读的流（AUTO_CLOSE_SOURCE），
                // 直接把 ZipInputStream 交给它，后续 getNextEntry 就会撞「Stream closed」。
                byte[] bytes = zip.readAllBytes();
                try (InputStream meta = new java.io.ByteArrayInputStream(bytes)) {
                    if (name.equals("fabric.mod.json")) {
                        found.add(parseFabric(meta));
                    }
                    else {
                        found.add(parseToml(meta));
                    }
                }
            }
        }
        return found;
    }

    /** fabric.mod.json：{@code id} / {@code provides} / {@code depends}。 */
    private ModMeta parseFabric(InputStream json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        String id = root.path("id").asText("");
        Set<String> provides = new LinkedHashSet<>();
        root.path("provides").forEach(node -> {
            String value = node.asText("");
            if (!value.isBlank()) {
                provides.add(value);
            }
        });
        Map<String, Boolean> requires = new LinkedHashMap<>();
        JsonNode depends = root.path("depends");
        if (depends.isObject()) {
            depends.fieldNames().forEachRemaining(name -> requires.put(name, true));
        }
        return new ModMeta(id, Set.copyOf(provides), Map.copyOf(requires));
    }

    /**
     * mods.toml / neoforge.mods.toml 的最小读取。
     *
     * <p>只取 {@code modId} 与 {@code [[dependencies.<owner>]]} 块里的 {@code modId} /
     * {@code mandatory}：完整解析 TOML 需要额外依赖，而这两个键在 Forge 的元数据里形态稳定
     * （{@code modId="x"} 与 {@code mandatory=true}），行式状态机足够且可测。</p>
     */
    private ModMeta parseToml(InputStream toml) throws IOException {
        String text = new String(toml.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String id = "";
        Set<String> provides = new LinkedHashSet<>();
        Map<String, Boolean> requires = new LinkedHashMap<>();
        boolean inDependencyBlock = false;
        String pendingModId = null;
        boolean pendingMandatory = false;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher block = TOML_DEPENDENCY_BLOCK.matcher(rawLine);
            if (block.matches()) {
                // 进入新的依赖块：先结算上一块
                if (pendingModId != null) {
                    requires.put(pendingModId, pendingMandatory);
                    pendingModId = null;
                    pendingMandatory = false;
                }
                inDependencyBlock = true;
                continue;
            }
            if (line.startsWith("[")) {
                // 其它段（如 [[mods]]）：结算依赖块
                if (pendingModId != null) {
                    requires.put(pendingModId, pendingMandatory);
                    pendingModId = null;
                    pendingMandatory = false;
                }
                inDependencyBlock = false;
                continue;
            }
            Matcher keyValue = TOML_KEY.matcher(rawLine);
            if (!keyValue.matches()) {
                continue;
            }
            String key = keyValue.group(1).toLowerCase(Locale.ROOT);
            String value = stripQuotes(keyValue.group(2));
            if ("modid".equals(key)) {
                if (inDependencyBlock) {
                    if (pendingModId != null) {
                        requires.put(pendingModId, pendingMandatory);
                        pendingMandatory = false;
                    }
                    pendingModId = value;
                }
                else if (id.isBlank()) {
                    id = value;
                }
            }
            else if ("mandatory".equals(key) && inDependencyBlock) {
                pendingMandatory = "true".equalsIgnoreCase(value);
            }
            else if ("provides".equals(key) && !value.isBlank()) {
                // 少数模组用它声明别名，按需收集即可
                provides.add(value);
            }
        }
        if (pendingModId != null) {
            requires.put(pendingModId, pendingMandatory);
        }
        return new ModMeta(id, Set.copyOf(provides), Map.copyOf(requires));
    }

    private static String stripQuotes(String value) {
        String text = value.trim();
        if (text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'")))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 结果按稳定的顺序输出（诊断行、日志行都用它）。 */
    public static List<String> describe(Report report) {
        List<String> lines = new ArrayList<>();
        lines.add("模组包排查：jar " + report.jarCount() + " 个，可识别模组元数据 " + report.modCount() + " 个");
        if (!report.corrupt().isEmpty()) {
            List<String> sample = report.corrupt().size() > 3
                    ? report.corrupt().subList(0, 3)
                    : report.corrupt();
            lines.add("模组包已损坏：" + report.corrupt().size() + " 个 jar 解压失败（包在打包或传输中被截断）："
                    + String.join("；", sample) + (report.corrupt().size() > 3 ? " …" : ""));
            lines.add("损坏的模组包无法用于渲染（解包阶段会直接失败，如 Unexpected end of ZLIB input stream），"
                    + "本次导入将不含模组：请在实例侧确认 mods 目录稳定（打包期间不要改动）后重新导入，"
                    + "或在地图详情页单独补传模组 jar");
        }
        if (!report.unreadable().isEmpty()) {
            List<String> sample = report.unreadable().size() > 3
                    ? report.unreadable().subList(0, 3)
                    : report.unreadable();
            lines.add("有 " + report.unreadable().size() + " 个 jar 无法识别模组元数据：" + String.join("；", sample)
                    + (report.unreadable().size() > 3 ? " …" : ""));
        }
        if (report.hasMissing()) {
            List<String> summary = report.missingSummary();
            int shown = Math.min(summary.size(), 6);
            for (String line : summary.subList(0, shown)) {
                lines.add("依赖缺失：" + line);
            }
            if (summary.size() > shown) {
                lines.add("依赖缺失：另有 " + (summary.size() - shown) + " 个模组存在缺失依赖");
            }
            lines.add("缺失依赖会让渲染退回静态解析（模组方块缺几何）；可在实例 mods 目录补齐后重新导入，"
                    + "或在地图详情页单独补传缺失的模组 jar");
        }
        return List.copyOf(lines);
    }

    /** 供日志/诊断截断显示：单条不超过 {@code max} 字符。 */
    public static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** 稳定排序的结果视图（测试与日志都有用）。 */
    public static List<String> sortedMissing(Map<String, Set<String>> missing) {
        return missing.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + " → " + String.join(",", new TreeSet<>(entry.getValue())))
                .toList();
    }
}
