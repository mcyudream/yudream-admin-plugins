package online.yudream.plugin.voxelithmap.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcpanel 集成的姿态：**软依赖 + 契约入口严格隔离**。
 *
 * <p>mcpanel 是可选集成：本插件主体（渲染、查看、标注）完全不依赖它，只有「从实例导入」尝试
 * 使用面板的实例文件契约。当前面板 0.22.6 没有该契约，因此适配器不引用契约类型，只在
 * {@code dependencyAvailable("mcpanel")} 之后按稳定类名反射查找；声明为 {@code softdepend}
 * 让本地开发不装面板也能完整启用本插件。契约缺失时适配器报告不可用，导入自动退回浏览器搬运
 * （见 {@code McpanelContractAbsentCompatibilityTest}）。</p>
 *
 * <p>契约类名/反射入口只允许出现在 {@code infrastructure/mcpanel} 适配器内：那里把
 * {@code RuntimeException}/{@code LinkageError} 一律收敛为「服务端导入不可用」——于是当前面板
 * （没有该契约）不会让插件报错，入口类也绝不能出现任何契约引用。</p>
 */
class McpanelIntegrationPostureTest {

    /** 唯一允许接触面板契约类名/反射入口的包。 */
    private static final String CONTRACT_HOME = "infrastructure/mcpanel/";

    /** 必须是软依赖：面板是可选集成，本地开发不装 mcpanel 也要能完整启用本插件。 */
    @Test
    void declaresMcpanelAsSoftDependency() throws Exception {
        assertTrue(dependencyList("softdepend").contains("mcpanel"),
                "plugin.yml 的 softdepend 应声明 mcpanel：面板是可选集成，本地开发不装它也要能启用本插件");
        assertTrue(!dependencyList("depend").contains("mcpanel"),
                "mcpanel 不应声明为 depend：那会强制所有部署都装面板，本地开发也不例外");
    }

    /** 入口类不得引用面板的 Java 契约（引用必须留在隔离的适配器里）。 */
    @Test
    void entryClassDoesNotReferenceProviderJavaContract() throws Exception {
        String path = "/online/yudream/plugin/voxelithmap/bootstrap/VoxelithMapPlugin.class";
        byte[] bytecode;
        try (InputStream input = McpanelIntegrationPostureTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "找不到入口类字节码：" + path);
            bytecode = input.readAllBytes();
        }
        String latin = new String(bytecode, StandardCharsets.ISO_8859_1);
        assertTrue(!latin.contains("mcpanel/api"),
                "入口类出现了 mcpanel 的 Java 契约引用：契约引用只允许出现在 infrastructure.mcpanel 的适配器里");
    }

    /**
     * 面板契约类型只允许出现在 {@code infrastructure/mcpanel}（适配器 + 可用性守卫）。
     *
     * <p>扫描编译产物而不是源码：源码里的字符串注释会被误判，而字节码里的类型引用才是运行时真正
     * 需要解析的东西。</p>
     */
    @Test
    void providerContractReferenceIsConfinedToTheAdapterPackage() throws Exception {
        Path packageRoot = packageRoot();
        List<String> offenders = new ArrayList<>();
        List<String> scanned = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(packageRoot)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".class")).toList()) {
                String relative = packageRoot.relativize(file).toString().replace('\\', '/');
                scanned.add(relative);
                if (relative.startsWith(CONTRACT_HOME)) {
                    continue;
                }
                String latin = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                if (latin.contains("mcpanel/api")) {
                    offenders.add(relative);
                }
            }
        }
        // 阳性对照：扫描确实覆盖了入口类与适配器之外的大量类，否则"没有违规"毫无意义
        assertTrue(scanned.size() > 10, "扫描范围过小，测试可能失效：" + scanned.size());
        assertTrue(offenders.isEmpty(),
                "mcpanel 契约引用只允许出现在 " + CONTRACT_HOME + " 内，实际还有：" + offenders);
    }

    /** 本插件类的根包目录（编译产物中的 {@code .../plugin/voxelithmap}）。 */
    private static Path packageRoot() throws Exception {
        URL entry = McpanelIntegrationPostureTest.class.getClassLoader()
                .getResource("online/yudream/plugin/voxelithmap/bootstrap/VoxelithMapPlugin.class");
        assertNotNull(entry, "找不到编译产物根目录（先执行 compile）");
        // <root>/online/yudream/plugin/voxelithmap/bootstrap/VoxelithMapPlugin.class → 上溯两级到 voxelithmap
        return Path.of(entry.toURI()).getParent().getParent();
    }

    /** 解析 plugin.yml 中某个依赖段的条目（缩进列表形式）。 */
    private static List<String> dependencyList(String section) throws Exception {
        String yml = pluginYml();
        Matcher block = Pattern.compile("^" + section + ":\s*$", Pattern.MULTILINE).matcher(yml);
        if (!block.find()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String line : yml.substring(block.end()).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            if (!line.startsWith(" ")) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (!trimmed.startsWith("-")) {
                break;
            }
            entries.add(trimmed.substring(1).trim());
        }
        return entries;
    }

    private static String pluginYml() throws Exception {
        String path = "/plugin.yml";
        try (InputStream input = McpanelIntegrationPostureTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "找不到 plugin.yml：" + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
