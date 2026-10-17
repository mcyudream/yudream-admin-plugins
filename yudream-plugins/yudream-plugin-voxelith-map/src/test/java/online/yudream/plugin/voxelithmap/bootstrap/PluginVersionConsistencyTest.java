package online.yudream.plugin.voxelithmap.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护插件版本的三处声明一致：{@link VoxelithMapPlugin#VERSION}（喂给 @PluginSpec 与
 * /health）与 plugin.yml 的 version（宿主 descriptor、assetRevision 与商店条目的来源）。
 *
 * <p>这里曾经吃过亏：VERSION 常量停留在 1.0.33，而 plugin.yml 一路升到 1.1.x——
 * 健康检查与生命周期日志上报的是错版本。每次升版三处必须同改，本测试防再犯。</p>
 */
class PluginVersionConsistencyTest {

    /** plugin.yml 的 version 必须与入口类常量完全一致。 */
    @Test
    void pluginYmlVersionMatchesConstant() throws Exception {
        String yml = resourceText("/plugin.yml");
        Matcher matcher = Pattern.compile("^version:\\s*(\\S+)", Pattern.MULTILINE).matcher(yml);
        assertTrue(matcher.find(), "plugin.yml 缺少 version 字段");
        assertEquals(VoxelithMapPlugin.VERSION, matcher.group(1),
                "plugin.yml 的 version 与 VoxelithMapPlugin.VERSION 不一致——升版时必须三处同步"
                        + "（pom.xml、plugin.yml、VERSION 常量）");
    }

    /** 语义化版本：宿主 assetRevision 用它拼缓存指纹，非法值会污染资源 URL。 */
    @Test
    void versionIsStableSemver() {
        assertTrue(VoxelithMapPlugin.VERSION.matches("\\d+\\.\\d+\\.\\d+"),
                "VERSION 应为 MAJOR.MINOR.PATCH：" + VoxelithMapPlugin.VERSION);
    }

    private static String resourceText(String path) throws Exception {
        try (InputStream input = Optional.ofNullable(PluginVersionConsistencyTest.class.getResourceAsStream(path))
                .orElseThrow(() -> new IllegalStateException("测试资源缺失: " + path))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
