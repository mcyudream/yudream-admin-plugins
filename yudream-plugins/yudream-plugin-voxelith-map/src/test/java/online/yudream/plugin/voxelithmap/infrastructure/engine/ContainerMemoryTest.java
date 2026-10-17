package online.yudream.plugin.voxelithmap.infrastructure.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「进程被系统杀掉」这类退出码的解释与内存水位读取。
 *
 * <p>线上那次失败只留下退出码 137（128+9，SIGKILL）与一句「未知退出码」，用户看不出原因，而现有文案
 * 还会顺手建议「调大 heap-mib」——对 OOM Killer 恰恰是反向操作（堆越大总占用越高、越容易被杀）。
 * 这里把两边都钉住：137/143 必须给出正确方向，且不再附带堆提示；内存水位解析要能吃 cgroup v2 / v1 两种布局。</p>
 */
class ContainerMemoryTest {

    @Test
    void explainsSignalExitCodesWithoutHeapAdvice() {
        String killed = VoxelithProcessRunner.describeExitCode(137);
        assertTrue(killed.contains("SIGKILL"), killed);
        assertTrue(killed.contains("OOM"), killed);
        assertTrue(killed.contains("而不是调大 heap-mib"), killed);
        assertTrue(killed.contains("边车"), "应给出可操作的内存削减方向：" + killed);

        String term = VoxelithProcessRunner.describeExitCode(143);
        assertTrue(term.contains("SIGTERM"), term);

        // 堆提示只对「与堆有关」的退出码生效
        assertTrue(VoxelithProcessRunner.heapHintApplies(4));
        assertTrue(VoxelithProcessRunner.heapHintApplies(3));
        assertFalse(VoxelithProcessRunner.heapHintApplies(137));
        assertFalse(VoxelithProcessRunner.heapHintApplies(143));
        assertFalse(VoxelithProcessRunner.heapHintApplies(0));
    }

    @Test
    void readsCgroupV2LimitAndUsage(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("memory.max"), "25769803776\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("memory.current"), "22870769664\n", StandardCharsets.UTF_8);
        Path meminfo = dir.resolve("meminfo");
        Files.writeString(meminfo, "MemTotal:       32768148 kB\nMemAvailable:    4311752 kB\n", StandardCharsets.UTF_8);

        ContainerMemory memory = ContainerMemory.readFrom(dir, meminfo);

        assertEquals(25769803776L, memory.limitBytes());
        assertEquals(22870769664L, memory.currentBytes());
        assertEquals(32768148L * 1024, memory.hostTotalBytes());
        assertEquals(4311752L * 1024, memory.hostAvailableBytes());
        String text = memory.describe();
        assertTrue(text.contains("容器内存上限 24.0 GiB"), text);
        assertTrue(text.contains("已用 21.3 GiB"), text);
        assertTrue(text.contains("主机 31.3 GiB"), text);
    }

    @Test
    void readsCgroupV1LayoutWhenV2IsAbsent(@TempDir Path dir) throws IOException {
        Path legacy = Files.createDirectories(dir.resolve("memory"));
        Files.writeString(legacy.resolve("memory.limit_in_bytes"), "17179869184\n", StandardCharsets.UTF_8);
        Files.writeString(legacy.resolve("memory.usage_in_bytes"), "8589934592\n", StandardCharsets.UTF_8);

        ContainerMemory memory = ContainerMemory.readFrom(dir, dir.resolve("missing-meminfo"));

        assertEquals(17179869184L, memory.limitBytes());
        assertEquals(8589934592L, memory.currentBytes());
        // 拿不到主机内存时只描述容器部分，不编造数字
        assertFalse(memory.describe().contains("主机"));
    }

    @Test
    void unknownWhenNothingIsReadable(@TempDir Path dir) {
        ContainerMemory memory = ContainerMemory.readFrom(dir, dir.resolve("nope"));
        assertFalse(memory.known());
        assertEquals("", memory.describe());
    }

    @Test
    void treatsHugeV1LimitAsUnlimited(@TempDir Path dir) throws IOException {
        // cgroup v1 用 2^63-1 之类的超大值表示「无限制」：记成未知，别把 8 EiB 写进失败原因
        Path legacy = Files.createDirectories(dir.resolve("memory"));
        Files.writeString(legacy.resolve("memory.limit_in_bytes"), String.valueOf(Long.MAX_VALUE), StandardCharsets.UTF_8);

        ContainerMemory memory = ContainerMemory.readFrom(dir, dir.resolve("none"));

        assertEquals(ContainerMemory.UNKNOWN, memory.limitBytes());
        assertFalse(memory.describe().contains("EiB"));
    }
}
