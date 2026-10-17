package online.yudream.plugin.voxelithmap.infrastructure.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 资源包文件名 → 采集版本（口令与上游 {@code McVersionResolver} 一致）。
 *
 * <p>这条判据直接决定 models.json.gz 与 client jar 是否同版本，错一次就是整张地图品红，
 * 所以把上游测试里的用例照抄过来钉住。</p>
 */
class MinecraftVersionsTest {

    @Test
    void 从文件名里抽出版本号() {
        assertEquals("1.21.1", MinecraftVersions.versionOf("client-1.21.1.jar"));
        assertEquals("1.20.1", MinecraftVersions.versionOf("1.20.1.jar"));
        assertEquals("1.20.6", MinecraftVersions.versionOf("我的整合包 1.20.6 client.jar"));
        assertEquals("1.21", MinecraftVersions.versionOf("client-1.21.jar"));
    }

    @Test
    void 文件名里没有版本号时返回空() {
        assertNull(MinecraftVersions.versionOf("client.jar"));
        assertNull(MinecraftVersions.versionOf(""));
        assertNull(MinecraftVersions.versionOf(null));
        // 别把 mod 的版本号当成 MC 版本
        assertNull(MinecraftVersions.versionOf("fabric-api-0.92.2.jar"));
    }

    @Test
    void 落盘名只保留文件名本身() {
        assertEquals("client-1.21.1.jar", MinecraftVersions.safeJarFileName("client-1.21.1.jar"));
        // 带路径（浏览器可能给相对路径，Windows 可能给反斜杠）
        assertEquals("evil.jar", MinecraftVersions.safeJarFileName("../../etc/evil.jar"));
        assertEquals("client.jar", MinecraftVersions.safeJarFileName("C:\\tmp\\client.jar"));
        // 空白原样保留：上游按同一份文件名判版本
        assertEquals("我的整合包 1.20.6 client.jar",
                MinecraftVersions.safeJarFileName("我的整合包 1.20.6 client.jar"));
    }

    @Test
    void 不是jar或不可用时退回中性名() {
        assertNull(MinecraftVersions.safeJarFileName("world.zip"));
        assertNull(MinecraftVersions.safeJarFileName("nothing"));
        assertNull(MinecraftVersions.safeJarFileName("   "));
        assertNull(MinecraftVersions.safeJarFileName(null));
        assertEquals(MinecraftVersions.FALLBACK_JAR_NAME, MinecraftVersions.jarFileName(null));
        assertEquals(MinecraftVersions.FALLBACK_JAR_NAME,
                MinecraftVersions.jarFileName("../../etc/passwd"));
        assertEquals("client-1.21.1.jar", MinecraftVersions.jarFileName("client-1.21.1.jar"));
    }
}
