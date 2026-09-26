package online.yudream.base.plugin.mcpanel.infrastructure.repository;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端口分配并发回归：同键并发 allocate 只有一个赢家（单 JVM 锁边界）。 */
class DocumentPortAllocationRepositoryTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final DocumentPortAllocationRepository ports =
            new DocumentPortAllocationRepository(documents, McpanelJson.mapper());

    @BeforeEach
    void clean() {
        documents.clear();
    }

    @Test
    void allocateIsIdempotentForSameInstanceAndRejectsOtherOwner() {
        assertTrue(ports.allocate("node-1", 25565, "tcp", "inst-a"));
        assertTrue(ports.allocate("node-1", 25565, "tcp", "inst-a"));
        assertEquals("inst-a", ports.ownerOf("node-1", 25565, "tcp").orElseThrow());
        // 他实例抢占已占用端口：仓储拒绝（返回 false）。
        org.junit.jupiter.api.Assertions.assertFalse(ports.allocate("node-1", 25565, "tcp", "inst-b"));
    }

    @Test
    void releaseAllowsReallocation() {
        assertTrue(ports.allocate("node-1", 25566, "tcp", "inst-a"));
        assertTrue(ports.release("node-1", 25566, "tcp"));
        assertTrue(ports.allocate("node-1", 25566, "tcp", "inst-b"));
        assertEquals("inst-b", ports.ownerOf("node-1", 25566, "tcp").orElseThrow());
    }

    @Test
    void concurrentAllocateSameKeyHasExactlyOneWinner() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final String owner = "inst-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return ports.allocate("node-1", 25567, "tcp", owner);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            int winners = 0;
            for (java.util.concurrent.Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
            assertEquals(1L, ports.countByNode("node-1"));
        } finally {
            pool.shutdownNow();
            org.junit.jupiter.api.Assertions.assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void findByInstanceAndCountAcrossPages() {
        for (int i = 0; i < 5; i++) {
            ports.allocate("node-1", 20000 + i, "tcp", "inst-pages");
        }
        assertEquals(5, ports.findByInstance("inst-pages").size());
        assertEquals(5L, ports.countByNode("node-1"));
    }
}
