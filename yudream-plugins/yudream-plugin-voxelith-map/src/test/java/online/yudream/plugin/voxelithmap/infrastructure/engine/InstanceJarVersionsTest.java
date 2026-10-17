package online.yudream.plugin.voxelithmap.infrastructure.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例根目录 jar 文件名 → 存档版本。
 *
 * <p>判据必须与前端旧实现、渲染引擎同式（同一个 {@code 1.x[.y]} 正则），否则「导入时显示
 * 1.21.1、渲染按 1.20.1 采集」会成片品红与空洞，而用户完全看不出原因。</p>
 */
class InstanceJarVersionsTest {

    @Test
    void prefersServerCoreJarOverOtherJars() {
        // 目录里依赖 jar 先出现，但服务端核心命名才是这套实例真正在跑的版本
        assertEquals(Optional.of("1.21.1"), InstanceJarVersions.pickVersion(List.of(
                "library-1.2.3.jar",
                "fabric-server-mc.1.21.1-launcher.1.0.1.jar",
                "mod-1.20.1.jar")));
    }

    @Test
    void recognizesCommonServerCoreNames() {
        assertEquals(Optional.of("1.20.4"), InstanceJarVersions.pickVersion(List.of("paper-1.20.4-497.jar")));
        assertEquals(Optional.of("1.19.4"), InstanceJarVersions.pickVersion(List.of("purpur-1.19.4.jar")));
        assertEquals(Optional.of("1.20.1"), InstanceJarVersions.pickVersion(List.of("forge-1.20.1-47.2.0.jar")));
        assertEquals(Optional.of("1.16.5"), InstanceJarVersions.pickVersion(List.of("mohist-1.16.5.jar")));
        assertEquals(Optional.of("1.20.1"), InstanceJarVersions.pickVersion(List.of("arclight-1.20.1.jar")));
        assertEquals(Optional.of("1.18.2"), InstanceJarVersions.pickVersion(List.of("vanilla-1.18.2.jar")));
    }

    @Test
    void fallsBackToFirstVersionedJar() {
        assertEquals(Optional.of("1.20.1"), InstanceJarVersions.pickVersion(List.of(
                "readme.txt", "something-1.20.1.jar")));
    }

    @Test
    void ignoresNonJarsAndUnversionedNames() {
        assertTrue(InstanceJarVersions.pickVersion(List.of("world", "mods", "opts.txt", "client.jar")).isEmpty());
        assertTrue(InstanceJarVersions.pickVersion(List.of()).isEmpty());
        assertTrue(InstanceJarVersions.pickVersion(null).isEmpty());
    }
}
