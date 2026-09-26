package online.yudream.base.plugin.mcpanel.domain.repo;

import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeQuery;
import online.yudream.base.plugin.mcpanel.domain.valobj.PageResult;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public interface McpanelNodeRepository {

    McpanelNode save(McpanelNode node);

    Optional<McpanelNode> findById(String nodeId);

    /**
     * 节点分页。真实 total：先按 200/页遍历全部节点，再过滤/排序/切片，
     * 过滤与不过滤均返回真实总数，不存在 200 截断。
     */
    PageResult<McpanelNode> page(NodeQuery query);

    /**
     * 读-合并-写单节点（同宿主内按 nodeId 固定条纹锁串行，admin 配置写与
     * runtime 状态写必须共用本方法，禁止绕过自行 findById+save）。
     * merger 收到仓内当前节点；返回 null 表示放弃本次写入，方法返回 null；
     * 节点不存在返回 null。
     */
    McpanelNode update(String nodeId, UnaryOperator<McpanelNode> merger);

    /**
     * 同节点条纹锁内执行 action（与 {@link #update}、注册合并共用同一把锁，
     * 可重入）。bootstrap 的 token 领取/吊销 + 注册 CAS、重签吊销必须包在其内，
     * 保证同宿主注册事务对同一节点串行；跨实例由各 CAS 原语兜底。
     */
    <T> T withNodeLock(String nodeId, Supplier<T> action);

    /**
     * 注册合并（M1 唯一注册写入点）：内部自取同一条纹锁并读取仓内<b>最新</b>节点，
     * 仅合并注册字段（agentVersion/reportedHost/reportedCertSha256/enrolledAtMs/
     * updatedAt），<b>不采用外部整快照</b>，杜绝覆盖管理员刚写入的 endpoint/pin 等
     * 配置；再以宿主单文档 CAS（enrolledAtMs ≤ 0）兜底跨实例唯一注册。
     *
     * <p>{@code pinToRegister}：enroll TOFU——pinned 节点管理员未预填指纹时，由
     * 调用方传入节点上报指纹作为初始 pin；仅当仓内节点 pin 为空白时才写入，
     * 绝不覆盖已有值；null/空白 = 不登记。
     *
     * <p>已注册或节点不存在返回 null（不写），调用方回 409 auth.tokenConsumed。
     */
    McpanelNode markEnrolledOnce(String nodeId, String agentVersion, String reportedHost,
                                 String reportedCertSha256, String pinToRegister, long nowMs);

    void delete(String nodeId);
}
