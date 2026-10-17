package online.yudream.plugin.voxelithmap.infrastructure.storage;

import online.yudream.plugin.voxelithmap.infrastructure.engine.VoxelithRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整任务日志的落盘、读取与清理。
 *
 * <p>起因：任务文档只留日志尾部（4000 字），而子进程的完整输出原本写在任务临时目录里、随任务一起删掉，
 * 于是「日志不完整」是必然。这里守住三件事：收尾时能搬到持久目录、按任务 id 读得回、过期能清掉。</p>
 */
class TaskLogStoreTest {

    @TempDir
    Path tempDir;

    private TaskLogStore store() {
        VoxelithRuntimeConfig config = new VoxelithRuntimeConfig(
                Path.of("java"), tempDir.resolve("runtime"), null, List.of("worker.jar"), null,
                4096, 180, 1, false, 0, 2048, true);
        return new TaskLogStore(config);
    }

    private Path source(String name, String text) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void keepsAndReadsBackTheFullLog() throws IOException {
        TaskLogStore store = store();
        String full = "line-1\nline-2\nline-3\n";

        assertTrue(store.keep("task-abc", source("render.log", full)));

        Path kept = store.find("task-abc").orElseThrow();
        assertEquals(full, Files.readString(kept, StandardCharsets.UTF_8));
        assertEquals(full.getBytes(StandardCharsets.UTF_8).length, store.sizeOf("task-abc"));
    }

    @Test
    void missingLogIsEmptyNotAnError() {
        TaskLogStore store = store();
        assertTrue(store.find("task-nope").isEmpty());
        assertTrue(store.find(null).isEmpty());
        assertEquals(0, store.sizeOf("task-nope"));
        assertFalse(store.keep("task-x", null));
        assertFalse(store.keep(null, Path.of("whatever.log")));
    }

    @Test
    void taskIdIsSanitisedBeforeBecomingAFileName() throws IOException {
        TaskLogStore store = store();
        // 任务 id 由插件生成，但路径拼接一律白名单化：任何穿越形态都落成普通文件名
        assertTrue(store.keep("../../etc/passwd", source("a.log", "x")));

        Path kept = store.find("../../etc/passwd").orElseThrow();
        assertEquals(kept.getParent(), kept.getParent().resolve(".").normalize());
        assertTrue(kept.getFileName().toString().startsWith(".._.._etc_passwd"), kept.toString());
        assertTrue(kept.startsWith(Files.createDirectories(tempDir.resolve("runtime/logs"))));
    }

    @Test
    void pruneRemovesOnlyExpiredLogs() throws IOException {
        TaskLogStore store = store();
        store.keep("task-fresh", source("fresh.log", "fresh"));
        store.keep("task-old", source("old.log", "old"));
        Path old = store.find("task-old").orElseThrow();
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - Duration.ofDays(40).toMillis()));

        int removed = store.prune(Duration.ofDays(30));

        assertEquals(1, removed);
        assertTrue(store.find("task-old").isEmpty());
        assertTrue(store.find("task-fresh").isPresent());
        // 保留期非法时不动任何文件
        assertEquals(0, store.prune(Duration.ZERO));
        assertTrue(store.find("task-fresh").isPresent());
    }

    @Test
    void deleteForRemovesTheGivenTasks() throws IOException {
        TaskLogStore store = store();
        store.keep("task-1", source("1.log", "1"));
        store.keep("task-2", source("2.log", "2"));

        assertEquals(2, store.deleteFor(List.of("task-1", "task-2", "task-absent")));

        assertTrue(store.find("task-1").isEmpty());
        assertTrue(store.find("task-2").isEmpty());
    }
}
