package online.yudream.plugin.voxelithmap.infrastructure.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 随插件打包的 Voxelith 运行时。
 *
 * <p>三件产物全部以**只读副本**形式打进插件 JAR（见 THIRD-PARTY-NOTICES/Voxelith-MIT.txt）：</p>
 * <ul>
 *   <li>{@code voxelith/server/voxelith-server.jar} —— Spring Boot fat jar。既用于
 *       {@linkplain VoxelithSidecar 边车}（{@code java -jar}），也用于所有 CLI
 *       （{@code -cp <jar> org.springframework.boot.loader.launch.PropertiesLauncher}
 *       + {@code -Dloader.main=<Cli>}）；</li>
 *   <li>{@code voxelith/worker/runtime-worker.jar} 与 {@code voxelith/worker/gson-2.13.1.jar}
 *       —— 采集 worker 子进程的普通 classpath（fat jar 里的嵌套 jar 不能被普通
 *       {@code -cp} 引用，因此必须单独带一份普通 jar）。</li>
 * </ul>
 *
 * <p>解出时校验 SHA-256：产物是二进制，静默损坏（半截下载、构建间混包）会表现成
 * 运行期莫名其妙的 NoClassDefFoundError，不如在解出阶段就失败并说清是哪个文件。</p>
 */
public final class VoxelithBundledRuntime {

    /** 打进 JAR 的资源路径。 */
    public static final String SERVER_JAR_RESOURCE = "voxelith/server/voxelith-server.jar";
    public static final String WORKER_JAR_RESOURCE = "voxelith/worker/runtime-worker.jar";
    public static final String GSON_JAR_RESOURCE = "voxelith/worker/gson-2.13.1.jar";

    /** 期望哈希（voxelith@4200402 + 工作区群系修复 PackBiomeSource，2026-09-28 重构建产物；1.0.16 曾误用修复前构建）。 */
    private static final Map<String, String> EXPECTED_SHA256 = Map.of(
            SERVER_JAR_RESOURCE, "a129fa8ac3a6f13fd099ea8997fc5ac147bf99eea1ccfaa1795b0b0b0fe090c7",
            WORKER_JAR_RESOURCE, "402e817aef26a6f781d29ee8a2faf2316bff7ce20dbcff62b6c167f86142edbf",
            GSON_JAR_RESOURCE, "94855942d4992f112946d3de1c334e709237b8126d8130bf07807c018a4a2120");

    /** 运行时版本标记：随 bundled 产物升级而下发，避免旧解压结果被复用。 */
    public static final String RUNTIME_MARKER = "voxelith-a129fa8a";

    private VoxelithBundledRuntime() {
    }

    /** Spring Boot 启动器中用于「指定另一个 main 类」的启动器（CLI 调用的关键）。 */
    public static String propertiesLauncherClass() {
        return "org.springframework.boot.loader.launch.PropertiesLauncher";
    }

    /**
     * 把 bundled 运行时解到 {@code runtimeDir}，返回解出的路径。
     *
     * <p>幂等：marker 文件内容匹配且三个 jar 的哈希都对时直接复用，不重复写盘
     * （引擎 jar 有 23MB，每次渲染都重解一遍纯属浪费）。</p>
     *
     * @param runtimeDir 运行根下的 {@code runtime/} 目录
     * @throws IOException 资源缺失、写盘失败或哈希不符
     */
    public static MaterializedRuntime materialize(Path runtimeDir) throws IOException {
        Path directory = runtimeDir.resolve(RUNTIME_MARKER);
        Path serverJar = directory.resolve("voxelith-server.jar");
        Path workerJar = directory.resolve("runtime-worker.jar");
        Path gsonJar = directory.resolve("gson-2.13.1.jar");
        Path marker = directory.resolve(".ready");

        if (Files.isRegularFile(marker) && Files.isRegularFile(serverJar)
                && Files.isRegularFile(workerJar) && Files.isRegularFile(gsonJar)) {
            if (verify(serverJar)) {
                return new MaterializedRuntime(serverJar, workerJar, gsonJar, directory);
            }
            // 哈希不符：清掉重解（可能是上次写到一半被中断）
            Files.deleteIfExists(marker);
        }

        Files.createDirectories(directory);
        extract(SERVER_JAR_RESOURCE, serverJar);
        extract(WORKER_JAR_RESOURCE, workerJar);
        extract(GSON_JAR_RESOURCE, gsonJar);
        Files.writeString(marker, RUNTIME_MARKER + "\n");
        pruneStaleRuntimes(runtimeDir, directory);
        return new MaterializedRuntime(serverJar, workerJar, gsonJar, directory);
    }

    /** 采集 worker 子进程的普通 classpath（顺序即优先级：worker 在前，gson 在后）。 */
    public static List<String> workerClasspath(MaterializedRuntime runtime) {
        return List.of(runtime.workerJar().toString(), runtime.gsonJar().toString());
    }

    /** 资源是否齐全（供管理页做「引擎可用性」自检，不抛异常）。 */
    public static Map<String, Object> describeBundledResources() {
        Map<String, Object> result = new LinkedHashMap<>();
        ClassLoader loader = VoxelithBundledRuntime.class.getClassLoader();
        for (String resource : List.of(SERVER_JAR_RESOURCE, WORKER_JAR_RESOURCE, GSON_JAR_RESOURCE)) {
            try (InputStream input = loader.getResourceAsStream(resource)) {
                result.put(resource, input != null);
            } catch (IOException e) {
                result.put(resource, false);
            }
        }
        return result;
    }

    /**
     * 清掉旧版本标记留下的运行时目录。
     *
     * <p>每次换代都会多解出一份 23MB（fat jar）+ 若干 worker jar，留着只会白占盘；而且那些目录
     * 里的旧引擎一旦被手工引用过，排障时更难看出真正生效的是哪一份。</p>
     *
     * <p>只删运行根下、名字以 {@code voxelith-} 开头、且不是本次标记的<b>目录</b>，出错一律忽略——
     * 清理失败绝不该影响渲染。</p>
     */
    private static void pruneStaleRuntimes(Path runtimeDir, Path current) {
        try (var children = Files.list(runtimeDir)) {
            children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("voxelith-"))
                    .filter(path -> !path.equals(current))
                    .forEach(VoxelithBundledRuntime::deleteQuietly);
        } catch (IOException ignored) {
            // 清理只是尽力而为
        }
    }

    private static void deleteQuietly(Path directory) {
        try (var stream = Files.walk(directory)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 单个文件删不掉不致命
                }
            });
        } catch (IOException ignored) {
            // 遍历失败同理
        }
    }

    private static void extract(String resource, Path destination) throws IOException {
        ClassLoader loader = VoxelithBundledRuntime.class.getClassLoader();
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        if (loader.getResourceAsStream(resource) == null) {
            throw new IOException("插件缺少内置引擎资源: " + resource);
        }
        MessageDigest digest = digest();
        try (InputStream input = loader.getResourceAsStream(resource);
             OutputStream fileOut = Files.newOutputStream(temporary);
             DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest)) {
            input.transferTo(digestOut);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        String expected = EXPECTED_SHA256.get(resource);
        if (expected != null && !expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(temporary);
            throw new IOException("内置引擎资源校验失败: " + resource
                    + "（期望 " + expected + "，实际 " + actual + "）");
        }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean verify(Path file) {
        String expected = EXPECTED_SHA256.get(SERVER_JAR_RESOURCE);
        try {
            MessageDigest digest = digest();
            try (InputStream input = Files.newInputStream(file);
                 DigestOutputStream sink = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                input.transferTo(sink);
            }
            return expected.equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            return false;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 解出后的运行时三件产物。
     *
     * @param serverJar fat jar（边车与所有 CLI 共用）
     * @param workerJar 采集 worker 普通 jar
     * @param gsonJar   worker 的 gson 依赖普通 jar
     * @param directory 解出目录
     */
    public record MaterializedRuntime(Path serverJar, Path workerJar, Path gsonJar, Path directory) {
    }
}
