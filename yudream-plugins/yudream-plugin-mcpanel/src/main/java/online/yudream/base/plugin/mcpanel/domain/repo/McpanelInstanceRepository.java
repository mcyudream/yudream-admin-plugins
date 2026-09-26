package online.yudream.base.plugin.mcpanel.domain.repo;

import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;

import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** 实例仓储（文档存储）。 */
public interface McpanelInstanceRepository {

    void save(McpanelInstance instance);

    Optional<McpanelInstance> findById(String id);

    /** 全量遍历（每页 200 直到取尽）——筛选/排序在调用方完成，total 恒为真实总数。 */
    List<McpanelInstance> findAll();

    void delete(String id);

    /**
     * 原子整记录变更（CAS）：以存储中的最新文档为基应用 mutator，经文档存储
     * CAS（rev 单调）整体替换——运行时字段（state/lastExitCode）与元数据字段
     * （如域名分配 slug/开关）都走这条路径，避免旧读覆盖并发写。
     * - 文档不存在（或 CAS 期间被删除）返回 false，绝不复活已删记录；
     * - CAS 期间其他写者先落库时自动以最新文档重试，旧读不会覆盖新写；
     * - mutator 必须只做纯内存变换，不得发 RPC/加锁（避免锁跨同步调用）。
     * 限定单 JVM 面板实例为唯一写者（与端口分配同一 M2 边界）。
     */
    boolean mutate(String id, UnaryOperator<McpanelInstance> mutator);

    /** 原子状态迁移（运行时字段专用，如 state/lastExitCode/updatedAt）：语义同 {@link #mutate}。 */
    boolean mutateState(String id, UnaryOperator<McpanelInstance> mutator);
}
