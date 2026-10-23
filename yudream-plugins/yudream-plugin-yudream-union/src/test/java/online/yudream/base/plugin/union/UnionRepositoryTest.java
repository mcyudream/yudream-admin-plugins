package online.yudream.base.plugin.union;

import online.yudream.base.plugin.union.domain.aggregate.UnionAuditLog;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.union.support.UnionTestSupport.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnionRepositoryTest {

    @Test
    void auditPageSortsNewestFirstAndFiltersAllSearchableFields() {
        UnionRepository repository = repository();
        repository.saveAudit(new UnionAuditLog("old", "ADMIN", "alice", "peer.enroll", "sitmc", "注册受信站点", 1_000L));
        repository.saveAudit(new UnionAuditLog("middle", "SYSTEM", "scheduler", "retention.purge", "audit", "清理记录", 2_000L));
        repository.saveAudit(new UnionAuditLog("new", "ADMIN", "bob", "peer.delete", "sitmc", "删除受信站点", 3_000L));

        List<UnionAuditLog> records = repository.pageAudit(1, 20, "SITMC", "at", true);

        assertEquals(List.of("new", "old"), records.stream().map(UnionAuditLog::id).toList());
        assertEquals(2, repository.auditCount("sitmc"));
        assertTrue(repository.pageAudit(1, 20, "scheduler", "at", true).stream().anyMatch(log -> log.id().equals("middle")));
    }

    @Test
    void auditPageHonorsSortFieldAndOrder() {
        UnionRepository repository = repository();
        repository.saveAudit(new UnionAuditLog("a", "ADMIN", "bob", "peer.delete", "sitmc", "删除受信站点", 3_000L));
        repository.saveAudit(new UnionAuditLog("b", "ADMIN", "alice", "peer.enroll", "sitmc", "注册受信站点", 1_000L));
        repository.saveAudit(new UnionAuditLog("c", "SYSTEM", "scheduler", "retention.purge", "audit", "清理记录", 2_000L));

        List<UnionAuditLog> byActionAsc = repository.pageAudit(1, 20, null, "action", false);
        assertEquals(List.of("a", "b", "c"), byActionAsc.stream().map(UnionAuditLog::id).toList());

        List<UnionAuditLog> byActionDesc = repository.pageAudit(1, 20, null, "action", true);
        assertEquals(List.of("c", "b", "a"), byActionDesc.stream().map(UnionAuditLog::id).toList());

        List<UnionAuditLog> illegalFieldFallsBackToAtDesc = repository.pageAudit(1, 20, null, "hack;drop", true);
        assertEquals(List.of("a", "c", "b"), illegalFieldFallsBackToAtDesc.stream().map(UnionAuditLog::id).toList());
    }

    @Test
    void purgeAuditBeforeScansAllPagesBeforeDeleting() {
        UnionRepository repository = repository();
        for (int index = 0; index < 250; index++) {
            long at = index < 201 ? 1_000L + index : 10_000L + index;
            repository.saveAudit(new UnionAuditLog("audit-" + index, "SYSTEM", "scheduler",
                    "retention.purge", "audit", "记录 " + index, at));
        }

        assertEquals(201, repository.purgeAuditBefore(5_000L));
        assertEquals(49, repository.auditCount(null));
        assertTrue(repository.pageAudit(1, 200, null, "at", true).stream().allMatch(log -> log.at() >= 5_000L));
    }

    private UnionRepository repository() {
        return new UnionRepository(new InMemoryDocumentStore());
    }
}
