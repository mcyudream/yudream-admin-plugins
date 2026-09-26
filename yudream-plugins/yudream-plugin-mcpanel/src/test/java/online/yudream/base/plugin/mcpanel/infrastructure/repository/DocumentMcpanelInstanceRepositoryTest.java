package online.yudream.base.plugin.mcpanel.infrastructure.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实例仓储 CAS（mutateState）回归：防删后复活、防旧读覆盖新写、rev 单调。 */
class DocumentMcpanelInstanceRepositoryTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final DocumentMcpanelInstanceRepository repo =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());

    @BeforeEach
    void clean() {
        documents.clear();
    }

    private McpanelInstance instance(String id, String state) {
        return McpanelInstance.create(id, "node-1", "实例" + id, "paper", "1.21.4", "",
                "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 1000, 2048, List.of(), Map.of("keep", "yes"), null, "", 1L)
                .withBinding(null, 1L)
                .withState(state, null, 1L);
    }

    private Object rawRev(String id) {
        return documents.findById(DocumentMcpanelInstanceRepository.COLLECTION, id)
                .orElseThrow().get(DocumentMcpanelInstanceRepository.FIELD_REV);
    }

    @Test
    void mutateStateAppliesOnFreshBaseAndKeepsUnrelatedFields() {
        repo.save(instance("inst-1", "running"));
        boolean applied = repo.mutateState("inst-1",
                current -> current.withState("exited", 137, 999L));
        assertTrue(applied);
        McpanelInstance stored = repo.findById("inst-1").orElseThrow();
        assertEquals("exited", stored.state());
        assertEquals(137, stored.lastExitCode());
        // 配置等非 runtime 字段以最新文档为基，不被状态事件覆盖丢失。
        assertEquals("yes", stored.config().get("keep"));
        assertEquals(999L, stored.updatedAt());
    }

    @Test
    void mutateStateMissingDocumentReturnsFalseAndNeverResurrects() {
        assertFalse(repo.mutateState("ghost", current -> current.withState("exited", null, 1L)));
        repo.save(instance("inst-2", "running"));
        repo.delete("inst-2");
        assertFalse(repo.mutateState("inst-2", current -> current.withState("exited", null, 2L)));
        assertTrue(repo.findById("inst-2").isEmpty());
    }

    @Test
    void mutateStateRetriesOnConcurrentWriteAndLandsOnNewest() {
        repo.save(instance("inst-3", "running"));
        // 首轮 mutator 内注入一次并发写（rev 抢先 +1）：CAS 首轮失败，重试后落在新基线。
        AtomicBoolean injected = new AtomicBoolean();
        boolean applied = repo.mutateState("inst-3", current -> {
            if (injected.compareAndSet(false, true)) {
                repo.save(current.withState("restarted", null, 555L));
            }
            return current.withState("exited", 1, 777L);
        });
        assertTrue(applied);
        McpanelInstance stored = repo.findById("inst-3").orElseThrow();
        // 最终状态 = 并发写之后的新基线 + 本次迁移，而非旧读覆盖。
        assertEquals("exited", stored.state());
        assertEquals(777L, stored.updatedAt());
        // rev 每次写单调递增（初始 save + 注入 save + CAS 成功）
        assertEquals(3L, ((Number) rawRev("inst-3")).longValue());
    }

    @Test
    void noopMutationSkipsWrite() {
        repo.save(instance("inst-4", "running"));
        assertTrue(repo.mutateState("inst-4", current -> current));
        assertEquals(1L, ((Number) rawRev("inst-4")).longValue());
    }

    @Test
    void legacyRecordWithoutRevStillCasable() {
        // 旧版本落库无 rev（null 已按仓储约定以 ""/-1 哨兵落库）：revision() 视为 0，首次 CAS 仍可成功。
        Map<String, Object> legacy = new ObjectMapper().convertValue(instance("inst-5", "running"),
                new TypeReference<Map<String, Object>>() {
                });
        legacy.replaceAll((key, value) -> value == null ? "" : value);
        documents.save(DocumentMcpanelInstanceRepository.COLLECTION, "inst-5", legacy);
        assertTrue(repo.mutateState("inst-5", current -> current.withState("stopped", null, 2L)));
        assertEquals("stopped", repo.findById("inst-5").orElseThrow().state());
    }

    @Test
    void legacyConfigAutoRestartMigratesToEventTaskFlag() {
        // 旧「异常退出后自动拉起」占位（config.autoRestart，节点侧从未实现）：读侧迁移到
        // 聚合顶层 autoRestart 并从 config 摘除；下次整档写落库，读侧幂等。
        Map<String, Object> legacy = new ObjectMapper().convertValue(instance("inst-6", "exited"),
                new TypeReference<Map<String, Object>>() {
                });
        legacy.replaceAll((key, value) -> value == null ? "" : value);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = new java.util.LinkedHashMap<>(
                (Map<String, Object>) legacy.getOrDefault("config", Map.of()));
        config.put("autoRestart", true);
        legacy.put("config", config);
        documents.save(DocumentMcpanelInstanceRepository.COLLECTION, "inst-6", legacy);

        McpanelInstance loaded = repo.findById("inst-6").orElseThrow();
        assertTrue(loaded.autoRestart(), "占位开启应迁移为事件任务-自动重启");
        assertFalse(loaded.config().containsKey("autoRestart"), "遗留键在读侧摘除");

        // 迁移随下一次整档写落库；再次读取不回弹。
        assertTrue(repo.mutateState("inst-6", current -> current.withState("exited", 1, 2L)));
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) documents
                .findById(DocumentMcpanelInstanceRepository.COLLECTION, "inst-6").orElseThrow();
        assertFalse(((Map<?, ?>) raw.get("config")).containsKey("autoRestart"), "落库后遗留键清除");
        assertEquals(Boolean.TRUE, raw.get("autoRestart"));
        assertTrue(repo.findById("inst-6").orElseThrow().autoRestart());
    }

    @Test
    void eventTaskFlagsRoundTripAndMutateIndependently() {
        repo.save(instance("inst-7", "exited").withEventTask(true, true, 2L));
        McpanelInstance loaded = repo.findById("inst-7").orElseThrow();
        assertTrue(loaded.autoRestart() && loaded.autoStart());
        assertTrue(repo.mutate("inst-7", current -> current.withEventTask(false, true, 3L)));
        McpanelInstance updated = repo.findById("inst-7").orElseThrow();
        assertFalse(updated.autoRestart());
        assertTrue(updated.autoStart());
        assertEquals("exited", updated.state(), "开关迁移不改运行状态");
    }
}
