package online.yudream.plugin.voxelithmap.infrastructure.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 引擎运行配置（从宿主只读设置项解析一次，随每次任务下传）。
 *
 * <p>所有路径字段在构造时统一规范化成<b>绝对路径</b>。原因：这些路径会被原样交给渲染/采集/
 * 全景/分片 worker 子进程（{@code -cp}、{@code -Xlog:gc:file=}、{@code --work-dir}、{@code --packs} …），
 * 而子进程的工作目录是「本次任务的临时目录」而不是宿主的工作目录——相对路径会在子进程侧被按
 * 另一个基准再拼一次，表现为 JVM 直接起不来（{@code Error opening log file ... No such file or
 * directory}）或 {@code 找不到或无法加载主类 ... PropertiesLauncher}（{@code -cp} 指到了不存在的 jar）。
 * 因此在唯一入口一次解决，别指望每个调用点各写一遍绝对化。</p>
 *
 * @param javaExecutable       java 可执行文件（默认取当前 JVM 的 {@code java.home}/bin/java）
 * @param runtimeRoot          插件运行根目录（引擎解出、任务临时目录、models 缓存、边车目录都在它下面）
 * @param externalServerJar    外部引擎 jar；为 {@code null} 时使用随插件打包的 fat jar（开发/排障逃逸口）
 * @param externalWorkerClasspath 外部 worker classpath 覆盖；为空时使用随包提取的两件普通 jar
 * @param modelsFile           显式指定的 {@code models.json.gz}；为 {@code null} 时用缓存在运行根下的那份
 * @param heapMiB              渲染子进程堆上限（{@code 0} = 不传 -Xmx，跟随 JVM 默认）
 * @param timeoutMinutes       单次渲染超时（分钟）
 * @param maxConcurrentTasks   同时执行的渲染任务数
 * @param sidecarEnabled       是否启用边车（二维预览与上传编目）
 * @param sidecarPort          边车端口；{@code 0} = 自动挑一个空闲端口
 * @param sidecarHeapMiB       边车堆上限
 */
public record VoxelithRuntimeConfig(
        Path javaExecutable,
        Path runtimeRoot,
        Path externalServerJar,
        java.util.List<String> externalWorkerClasspath,
        Path modelsFile,
        int heapMiB,
        int timeoutMinutes,
        int maxConcurrentTasks,
        boolean sidecarEnabled,
        int sidecarPort,
        int sidecarHeapMiB,
        /** 渲染子进程 OOM（退出码 4）时自动以双倍堆重试一次。 */
        boolean oomAutoRetry) {

    public static final int DEFAULT_HEAP_MIB = 4096;
    public static final int DEFAULT_TIMEOUT_MINUTES = 180;
    public static final int DEFAULT_SIDECAR_HEAP_MIB = 2048;

    public VoxelithRuntimeConfig {
        externalWorkerClasspath = externalWorkerClasspath == null
                ? java.util.List.of() : java.util.List.copyOf(externalWorkerClasspath);
        if (javaExecutable == null) {
            throw new IllegalArgumentException("java 可执行文件不能为空");
        }
        if (runtimeRoot == null) {
            throw new IllegalArgumentException("运行根目录不能为空");
        }
        // 见类注释：这些值最终都进子进程命令行，相对路径必须在宿主侧就解析掉
        javaExecutable = absolute(javaExecutable);
        runtimeRoot = absolute(runtimeRoot);
        externalServerJar = externalServerJar == null ? null : absolute(externalServerJar);
        modelsFile = modelsFile == null ? null : absolute(modelsFile);
        externalWorkerClasspath = externalWorkerClasspath.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(entry -> absolute(Path.of(entry)).toString())
                .toList();
        if (timeoutMinutes <= 0) {
            timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;
        }
        if (maxConcurrentTasks <= 0) {
            maxConcurrentTasks = 1;
        }
        if (sidecarHeapMiB <= 0) {
            sidecarHeapMiB = DEFAULT_SIDECAR_HEAP_MIB;
        }
    }

    /** 引擎解出目录。 */
    public Path runtimeDir() {
        return runtimeRoot.resolve("runtime");
    }

    /** 渲染任务临时目录的父目录（每个任务在其下建独立子目录，任务结束即删）。 */
    public Path tasksDir() {
        return runtimeRoot.resolve("tasks");
    }

    /**
     * 任务完整日志目录（持久）。
     *
     * <p>子进程输出全量写在任务临时目录里，而任务结束会删掉那个目录，于是只剩任务文档里的日志尾部
     * （4000 字）。收尾时把完整日志搬到这里，排查时才有全量可看。随地图删除一并清理。</p>
     */
    public Path logsDir() {
        return runtimeRoot.resolve("logs");
    }

    /**
     * 持久工作根：每张地图一个目录，保存 {@code renderMap} 的中间产物。
     *
     * <p>为什么不能跟着任务目录一起删：{@code renderMap} 会在工作目录里留下
     * {@code surface.png}/{@code surface.json}/{@code heightfield.bin}，而全景图（{@code PanoramaCli}）
     * 与分片 worker 都靠读它们工作。任务结束就删=这两项功能直接失效。</p>
     */
    public Path workRoot() {
        return runtimeRoot.resolve("work");
    }

    /** 某地图的持久工作目录（{@code renderMap} 的 {@code --work-dir}，跨次渲染复用）。 */
    public Path mapWorkDir(String mapId) {
        return workRoot().resolve(safeSegment(mapId));
    }

    /** 全景图与航拍图的产出根（其下按地图分目录）。 */
    public Path panoramaDir() {
        return runtimeRoot.resolve("panorama");
    }

    /**
     * 持久发布根：磁盘上的产物权威副本，布局 {@code <mapId>/<generationId>/}。
     *
     * <p>对象存储是给前端读的发布副本；磁盘这份是「还能被再加工」的工作副本——分片 worker
     * 要求 {@code publishDir/<mapId>} 已存在并把增量瓦片直接写进去，所以渲染完不能把它删掉。</p>
     */
    public Path publishRoot() {
        return runtimeRoot.resolve("publish");
    }

    /** 某地图的持久发布目录（分片 worker 的 {@code --publish-dir}，其下是各代目录）。 */
    public Path mapPublishDir(String mapId) {
        return publishRoot().resolve(safeSegment(mapId));
    }

    /** 某地图的全景产出目录（{@code PanoramaCli} 的 {@code --publish-dir}/{@code --map-id} 合成位置）。 */
    public Path mapPanoramaDir(String mapId) {
        return panoramaDir().resolve(safeSegment(mapId));
    }

    /**
     * 分片队列目录（多台机器指向同一共享目录即可协作）。
     *
     * <p>插件只负责托管 worker 进程，不负责生产队列——队列的写入方在 Voxelith 自己的管线侧。</p>
     */
    public Path queueDir() {
        return runtimeRoot.resolve("queue");
    }

    /** 绝对化 + 规范化：子进程的工作目录与宿主不同，相对路径必须在宿主侧解析（见类注释）。 */
    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** 路径段消毒：只允许字母数字与 {@code . _ -}，其余一律替换，避免拼出跨目录路径。 */
    private static String safeSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("地图 id 不能为空");
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.equals(".") || cleaned.equals("..")) {
            throw new IllegalArgumentException("非法地图 id: " + value);
        }
        return cleaned;
    }

    /** 显式配置的 {@code models.json.gz}；未配置返回 {@code null}。 */
    public Path configuredModelsFile() {
        return modelsFile;
    }

    /** 采集缓存根：其下按「采集版本 + 资源包」分桶。 */
    public Path modelsDir() {
        return runtimeRoot.resolve("models");
    }

    /**
     * 指定分桶下的 {@code models.json.gz}。
     *
     * <p>为什么必须分桶：{@code models.json.gz} 是「某个 MC 版本的渲染器烘焙出来的模型」，
     * 而贴图来自 client jar；两者版本不一致就是整片品红或空洞。此前只有一个共享文件，
     * 在「按资源包/存档自动判定采集版本」之后必然串味（1.21 的地图复用 1.20.1 的采集产物），
     * 因此按版本隔离——版本不同的地图各采一次，版本相同的照旧共用。</p>
     *
     * @param key 分桶名（形如 {@code 1.21.1-3f9c1a02}）；非法字符会被消毒成单层目录
     */
    public Path modelsCacheFile(String key) {
        return modelsDir().resolve(safeSegment(key)).resolve("models.json.gz");
    }

    /**
     * 找现成的采集产物（渲染与分片 worker 共用同一口径）。
     *
     * <p>分桶规则只在这里实现一次：调用方不要自己拼 {@code models-*.json.gz} 之类的路径，
     * 否则渲染用一个桶、worker 用另一个桶，worker 会静默退回静态解析（几何与原版有偏差）。</p>
     *
     * @param mcVersion       显式采集版本；留空/为 {@code null} = 自动（从资源包文件名判）
     * @param clientJarName   客户端 jar 原始文件名（可为 {@code null}）
     * @param clientJarFileId 客户端 jar 的平台文件 id（分桶用，可为 {@code null}）
     * @return 可用的 {@code models.json.gz}；没有现成的返回 {@code null}
     */
    public Path resolveModelsFile(String mcVersion, String clientJarName, String clientJarFileId) {
        return resolveModelsFile(mcVersion, clientJarName, clientJarFileId, java.util.List.of());
    }

    /** 同上，带模组：mod jar 参与 {@code modelsCacheKey} 分桶（见三参重载）。 */
    public Path resolveModelsFile(String mcVersion, String clientJarName, String clientJarFileId,
                                  java.util.List<String> modJarFileIds) {
        Path explicit = configuredModelsFile();
        if (explicit != null) {
            return Files.isRegularFile(explicit) ? explicit : null;
        }
        String version = mcVersion == null || mcVersion.isBlank()
                ? MinecraftVersions.versionOf(clientJarName) : mcVersion.trim();
        if (version == null || version.isBlank()) {
            return null;
        }
        Path candidate = modelsCacheFile(modelsCacheKey(version, clientJarFileId, modJarFileIds));
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /**
     * 采集缓存分桶键：{@code <版本>-<资源包摘要>}。
     *
     * <p>带上资源包身份是因为同一版本下换包（整合包/材质包）也会改变烘焙结果；摘要取平台文件 id
     * 而不是文件名——同一个包重传会拿到新 id，宁可多采一次，也不要复用别的包的模型。</p>
     */
    public static String modelsCacheKey(String version, String clientJarFileId) {
        return modelsCacheKey(version, clientJarFileId, java.util.List.of());
    }

    /**
     * 同上，带模组：{@code <版本>-<clientJar 摘要>-<mod 摘要>}。
     *
     * <p>mod jar 必须进桶键：{@code models.json.gz} 是「带 mod 烘出来的模型全表」，换模组
     * （增删改任一）都会改变模型集合——复用旧桶会让新模组的方块静默缺几何（静态解析兜底查不到），
     * 症状只有渲染日志里的 missing 方块清单，用户根本看不出原因。</p>
     */
    public static String modelsCacheKey(String version, String clientJarFileId,
                                        java.util.List<String> modJarFileIds) {
        String mods = modJarFileIds == null || modJarFileIds.isEmpty()
                ? "" : "-" + shortDigest(String.join("|", modJarFileIds));
        return version + "-" + shortDigest(clientJarFileId == null ? "" : clientJarFileId) + mods;
    }

    /** 取 SHA-256 前 8 位做短摘要（目录名可读且足够区分）。 */
    private static String shortDigest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /**
     * MC/Fabric 依赖下载缓存（采集 worker 用，约 100MB 量级）。
     *
     * <p>跨版本、跨地图共用一份：里面是 Mojang 库与 Fabric 加载器，本身就按版本分子目录，
     * 各次采集可增量复用。让它跟着采集工作目录走（CLI 的默认行为）会让每个版本桶、每张地图
     * 都重新下载一遍。</p>
     */
    public Path provisionCacheDir() {
        return runtimeRoot.resolve("provision-cache");
    }

    /** 边车的工作根（其 work/ 与 data/maps/ 都在其下）。 */
    public Path sidecarDir() {
        return runtimeRoot.resolve("sidecar");
    }

    public boolean usesBundledEngine() {
        return externalServerJar == null;
    }

    /** 是否具备采集能力（缺 worker classpath 时只能静态解析）。 */
    public boolean harvestCapable() {
        return !externalWorkerClasspath.isEmpty() || usesBundledEngine();
    }
}
