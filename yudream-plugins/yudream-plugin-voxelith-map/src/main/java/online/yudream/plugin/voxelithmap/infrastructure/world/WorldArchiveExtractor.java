package online.yudream.plugin.voxelithmap.infrastructure.world;

import online.yudream.plugin.voxelithmap.domain.value.InstanceDimensionDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 世界存档解包。
 *
 * <p>Voxelith 渲染要的是「含 region/ 与 level.dat 的目录」，而管理员上传的是 zip。
 * 上传的 zip 常见两种形态，都要认：</p>
 * <ul>
 *   <li>直接是存档内容（顶层就是 {@code level.dat}、{@code region/}）；</li>
 *   <li>外面套了一层目录（{@code 我的世界/level.dat}）——压缩包右键「压缩」的默认结果。</li>
 * </ul>
 *
 * <p>安全：拒绝 zip-slip 路径（{@code ..}、绝对路径、盘符），否则一个构造过的存档就能
 * 往宿主磁盘任意位置写文件。</p>
 */
public final class WorldArchiveExtractor {

    private WorldArchiveExtractor() {
    }

    /**
     * 老版存档的「维度套在世界里」布局：{@code world/DIM-1}（下界）、{@code world/DIM1}（末地）。
     *
     * <p>新版把维度放在存档**同级**（{@code world_nether}、{@code world_the_end}，各自带 level.dat），
     * 老版则塞在 {@code world} 里面，且这些子目录**没有 level.dat**。不认这种布局会有两个后果：
     * ①导入时只认出一个维度（下界/末地无处可切）；②渲染主世界时把 {@code world/DIM-1}、
     * {@code world/DIM1} 里的 region 也算进来，而它们的坐标与本维度毫无关系，于是「预览图里大片空白」
     * ——三个维度的包围盒并在一起，真正的世界只占一角。</p>
     *
     * <p>解法是解包时按**目标维度**把这种布局归一到新版形状：下界取 {@code DIM-1} 的内容放到
     * {@code world_nether}（并补一份 level.dat，让它像一个完整的存档根），末地同理，
     * 主世界则**丢掉**这两个子目录。归一之后下游（渲染与预览）看到的就是新版布局，不必各自再认一遍。</p>
     *
     * @param parentDir  世界目录名（{@code world}）
     * @param nestedDir  世界目录里的维度子目录名（{@code DIM-1} / {@code DIM1}）
     */
    private record LegacyLayout(String parentDir, String nestedDir, String dimension) {
    }

    /** 目标维度对应的新版目录名（{@code world_nether} / {@code world_the_end}）。 */
    private static String modernDirOf(String dimension) {
        return InstanceDimensionDir.dirFor(dimension);
    }

    /**
     * 识别老版嵌套布局：顶层存在 {@code world/level.dat}，且 {@code world/DIM-1} 或 {@code world/DIM1}
     * 里有条目。
     *
     * <p>只看条目名（中央目录），不解压；同时存在多种顶层世界目录时不做归一——那种包本来就没歧义。</p>
     */
    private static LegacyLayout detectLegacyLayout(ZipFile zip, String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return null;
        }
        String parent = null;
        List<String> nested = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = normalize(entries.nextElement().getName());
            if (name.isEmpty()) {
                continue;
            }
            // 只看前两段：老版布局是 <世界>/<DIM-1|DIM1>/...，父目录就是第一段
            String[] parts = name.split("/");
            if (parts.length < 2 || parts[0].isEmpty()) {
                // 顶层直接是 level.dat / region：新版扁平布局，没有嵌套维度可言
                continue;
            }
            String top = parts[0];
            if (parts.length == 2 && "level.dat".equalsIgnoreCase(parts[1])) {
                if (parent == null) {
                    parent = top;
                }
                else if (!parent.equals(top)) {
                    // 包里不止一个世界：本来就没歧义，不做归一
                    return null;
                }
                continue;
            }
            if (parts.length > 2 && InstanceDimensionDir.isLegacyNestedDir(parts[1])) {
                nested.add(parts[1]);
            }
        }
        if (parent == null || nested.isEmpty()) {
            return null;
        }
        if (InstanceDimensionDir.OVERWORLD.equals(dimension)) {
            // 主世界要的正是「把嵌套的 DIM 目录排除掉」，nestedDir 用不上，但归一必须发生
            return new LegacyLayout(parent, "", dimension);
        }
        String wanted = InstanceDimensionDir.legacyNestedDirName(dimension);
        if (wanted == null || nested.stream().noneMatch(dir -> dir.equalsIgnoreCase(wanted))) {
            return null;
        }
        return new LegacyLayout(parent, nested.stream()
                .filter(dir -> dir.equalsIgnoreCase(wanted))
                .findFirst()
                .orElse(wanted), dimension);
    }

    /**
     * 解包存档并返回真正的存档根（含 level.dat 的那一层）。
     *
     * @param archive       zip 文件
     * @param destination   解包目标目录
     * @return 存档根目录
     * @throws IOException 不是合法 zip、没有 level.dat、或路径非法
     */
    /**
     * 列出存档包**顶层**出现过的维度目录（现代布局：world / world_nether / world_the_end 并列）。
     *
     * <p>只认并列目录：旧版把下界/末地藏在 {@code world/DIM-1}、{@code world/DIM1} 下，那种包走
     * 「指定维度后解包」的老路径（{@link #extract} 会按维度定位到嵌套目录），不在这里展开——
     * 嵌套目录当成并列目录传下去，打包路径会算错。</p>
     *
     * @return 按主世界→下界→末地排序的维度目录；包内没有可识别目录时返回空表
     */
    public static List<InstanceDimensionDir> detectDimensionDirs(Path archive) throws IOException {
        Set<String> roots = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().replace('\\', '/');
                while (name.startsWith("/")) {
                    name = name.substring(1);
                }
                int slash = name.indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                String top = name.substring(0, slash);
                if (InstanceDimensionDir.dimensionOf(top).isPresent()) {
                    roots.add(top);
                }
            }
        }
        return InstanceDimensionDir.pick(new ArrayList<>(roots));
    }

    public static Path extract(Path archive, Path destination) throws IOException {
        return extract(archive, destination, null, null);
    }

    /**
     * 解包存档并返回**指定维度目录**对应的存档根。
     *
     * <p>一个存档包可以同时含 {@code world}/{@code world_nether}/{@code world_the_end}（实例导入就是这么
     * 打包的：一次传输建出多张维度地图）。解包本身不变，只是"哪一层是存档根"要按维度挑：
     * {@code rootHint} 给出该地图对应的目录名，就在解包结果里找这个名字的 {@code level.dat}。</p>
     *
     * <p>找不到时**回退自动判定**（不报错）：历史数据里 {@code worldName} 可能是地图名而不是目录名，
     * 不能因为一个过时的提示把渲染打断。</p>
     *
     * @param rootHint 存档根所在目录名（如 {@code world_nether}）；空 = 自动判定
     */
    public static Path extract(Path archive, Path destination, String rootHint) throws IOException {
        return extract(archive, destination, rootHint, null);
    }

    /**
     * 解包存档，按目标维度归一老版嵌套布局（{@code world/DIM-1}、{@code world/DIM1}），返回该维度的存档根。
     *
     * @param dimension 该地图的维度 id；用来决定「world 里的 DIM-1/DIM1 归给谁」，空 = 不归一
     */
    public static Path extract(Path archive, Path destination, String rootHint, String dimension)
            throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new IOException("存档文件不存在: " + archive);
        }
        Files.createDirectories(destination);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            LegacyLayout legacy = detectLegacyLayout(zip, dimension);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = normalize(entry.getName());
                if (name.isEmpty()) {
                    continue;
                }
                String relative = legacyRelative(legacy, name);
                if (relative == null) {
                    // 老版布局里不属于本维度的部分（另一个维度的 DIM 目录、主世界多余的 region）
                    continue;
                }
                Path target = safeResolve(destination, relative);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (legacy != null) {
                // 补一份 level.dat：DIM-1/DIM1 自己没有版本信息，缺了它下游（引擎的版本体检、
                // 预览的存档编目）都会失明；用同一个世界的 level.dat 是唯一正确的来源
                Path parentLevelDat = destination.resolve(legacy.parentDir()).resolve("level.dat");
                Path modernRoot = destination.resolve(modernDirOf(dimension));
                if (Files.isRegularFile(parentLevelDat) && Files.isDirectory(modernRoot)
                        && !Files.exists(modernRoot.resolve("level.dat"))) {
                    Files.copy(parentLevelDat, modernRoot.resolve("level.dat"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                // 主世界要丢掉嵌套的维度目录，否则它的 region 会被算进主世界的范围（预览图大片空白）
                if (InstanceDimensionDir.OVERWORLD.equals(dimension)) {
                    for (String nestedDir : List.of(InstanceDimensionDir.LEGACY_NETHER_DIR,
                            InstanceDimensionDir.LEGACY_END_DIR)) {
                        deleteQuietly(destination.resolve(legacy.parentDir()).resolve(nestedDir));
                    }
                }
            }
        }
        return locateWorldRoot(destination, rootHint);
    }

    /**
     * 老版布局下条目名 → 归一后的相对路径；返回 null 表示这条目不属于当前维度，应当跳过。
     *
     * <p>非老版布局（{@code legacy == null}）一律原样返回，解包行为与从前完全一致。</p>
     */
    private static String legacyRelative(LegacyLayout legacy, String name) {
        if (legacy == null) {
            return name;
        }
        String parentPrefix = legacy.parentDir() + "/";
        String nestedPrefix = parentPrefix + legacy.nestedDir() + "/";
        if (name.regionMatches(true, 0, nestedPrefix, 0, nestedPrefix.length())) {
            // 本维度的数据：DIM-1/... → world_nether/...
            return modernDirOf(legacy.dimension()) + "/" + name.substring(nestedPrefix.length());
        }
        if (name.equalsIgnoreCase(parentPrefix + "level.dat")) {
            // 保留世界根，既用来给上面的 DIM 目录补 level.dat，也让 rootHint 有东西可落位
            return name;
        }
        if (InstanceDimensionDir.OVERWORLD.equals(legacy.dimension())) {
            // 主世界本体：留下 world/region 等，但丢掉 world/DIM-1、world/DIM1
            for (String nestedDir : List.of(InstanceDimensionDir.LEGACY_NETHER_DIR,
                    InstanceDimensionDir.LEGACY_END_DIR)) {
                if (name.regionMatches(true, 0, parentPrefix + nestedDir + "/", 0,
                        parentPrefix.length() + nestedDir.length() + 1)) {
                    return null;
                }
            }
            return name;
        }
        // 下界/末地地图只需要本维度数据与 level.dat，其余（主世界的 region、另一个维度）都不带
        return null;
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException ignored) {
                    // 清理失败不影响渲染：留着的嵌套目录最多多占磁盘
                }
            });
        }
        catch (IOException ignored) {
            // 同上
        }
    }

    /**
     * jar 条目消费者：拿到文件名与该条目的完整字节。
     *
     * <p>刻意逐条回调而不是一次性返回 Map：mods 包动辄数百 MB，全部解出来堆在内存里
     * 正是要避免的（调用方可以逐条落存储，峰值内存只有最大的那一个 jar）。</p>
     */
    @FunctionalInterface
    public interface JarEntrySink {
        void accept(String fileName, byte[] content) throws IOException;
    }

    /**
     * 从磁盘上的模组包 zip 逐条读出 {@code .jar} 条目（实例 mods 目录整包导入用）。
     *
     * <p>走 {@link ZipInputStream} 流式读取，边读边回调，不把整包与全部 jar 同时留在内存；
     * 只认文件名以 {@code .jar} 结尾的条目（忽略大小写），目录与其它文件一律跳过。
     * 同名条目后来者覆盖。</p>
     *
     * @throws IOException 不是合法 zip 或读盘失败
     */
    public static void streamJarEntries(Path archive, JarEntrySink sink) throws IOException {
        requireZipMagic(archive);
        try (ZipInputStream stream = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = stream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalize(entry.getName());
                int slash = name.lastIndexOf('/');
                String fileName = slash >= 0 ? name.substring(slash + 1) : name;
                if (fileName.isBlank() || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                // readAllBytes 在 ZipInputStream 上天然止于当前条目边界
                byte[] content = stream.readAllBytes();
                if (content.length > 0) {
                    sink.accept(fileName, content);
                }
            }
        } catch (IOException e) {
            throw new IOException("模组包读取失败: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IOException("模组包不是合法的 zip: " + e.getMessage(), e);
        }
    }

    /**
     * 定位存档根：优先解包目录本身，其次唯一的一层子目录，最后递归找 level.dat。
     *
     * @throws IOException 找不到 level.dat
     */
    static Path locateWorldRoot(Path destination) throws IOException {
        return locateWorldRootGeneric(destination);
    }

    /**
     * 定位存档根：给定目录名时优先按目录名挑（按维度导入），否则退化为通用推断。
     */
    static Path locateWorldRoot(Path destination, String rootHint) throws IOException {
        Path hinted = locateByHint(destination, rootHint);
        if (hinted != null) {
            return hinted;
        }
        return locateWorldRootGeneric(destination);
    }

    /** 按目录名找存档根（{@code level.dat} 的所在目录名等于提示名，忽略大小写）；找不到返回 null。 */
    private static Path locateByHint(Path destination, String rootHint) {
        if (rootHint == null || rootHint.isBlank()) {
            return null;
        }
        String normalized = rootHint.trim().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        // lambda 里要引用，必须是有效 final：一次算好目录名本身
        String hint = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        List<Path> matches = new ArrayList<>();
        try (var stream = Files.walk(destination, 3)) {
            stream.filter(path -> path.getFileName().toString().equals("level.dat"))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().toLowerCase(java.util.Locale.ROOT).equals(hint))
                    .forEach(matches::add);
        }
        catch (IOException e) {
            return null;
        }
        if (matches.isEmpty()) {
            return null;
        }
        matches.sort((left, right) -> Integer.compare(left.getNameCount(), right.getNameCount()));
        return matches.get(0).getParent();
    }

    private static Path locateWorldRootGeneric(Path destination) throws IOException {
        if (Files.isRegularFile(destination.resolve("level.dat"))) {
            return destination;
        }
        List<Path> levelDats = new ArrayList<>();
        try (var stream = Files.walk(destination, 3)) {
            stream.filter(path -> path.getFileName().toString().equals("level.dat"))
                    .forEach(levelDats::add);
        }
        if (levelDats.isEmpty()) {
            throw new IOException("存档 zip 里找不到 level.dat（请确认上传的是世界存档而不是整合包）");
        }
        // 取层级最浅的那个：套壳目录的 level.dat 比 mod 内置资源里的更浅
        levelDats.sort((left, right) -> Integer.compare(left.getNameCount(), right.getNameCount()));
        return levelDats.get(0).getParent();
    }

    /**
     * 校验 zip 魔数。
     *
     * <p>{@link ZipInputStream} 对非 zip 字节是宽容的（{@code getNextEntry} 直接返回 null
     * 而不抛错），误传或截断的包会静默解出「零个模组」，被当成纯原版悄悄放过去。
     * 先看 PK 魔数把这个失败挡在明面上。</p>
     *
     * @throws IOException 不是 zip 或读盘失败
     */
    private static void requireZipMagic(Path archive) throws IOException {
        byte[] magic = new byte[4];
        int read;
        try (InputStream probe = Files.newInputStream(archive)) {
            read = probe.read(magic);
        }
        if (read < 4 || magic[0] != 'P' || magic[1] != 'K') {
            throw new IOException("模组包不是合法的 zip");
        }
    }

    /** 路径规范化：统一分隔符、去掉开头的 {@code ./}。 */
    static String normalize(String entryName) {
        String name = entryName.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        return name.trim();
    }

    /**
     * 把条目路径安全地落到目标目录内。
     *
     * @throws IOException 路径逃出目标目录
     */
    static Path safeResolve(Path destination, String name) throws IOException {
        if (name.contains("..") || name.contains(":")) {
            throw new IOException("存档 zip 内含非法路径: " + name);
        }
        Path resolved = destination.resolve(name).normalize();
        if (!resolved.startsWith(destination.normalize())) {
            throw new IOException("存档 zip 内含越界路径: " + name);
        }
        return resolved;
    }
}
