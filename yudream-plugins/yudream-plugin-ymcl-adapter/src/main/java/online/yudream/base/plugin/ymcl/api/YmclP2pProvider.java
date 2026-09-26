package online.yudream.base.plugin.ymcl.api;

import java.util.List;
import java.util.Optional;

/**
 * P2P 直连提供方（YAP §6.12 扩展点）：具备实例直连能力的插件（如 mcpanel）实现本接口，
 * 经 {@code context.registerExtension(YmclP2pProvider.class, impl)} 注册，适配器只聚合。
 *
 * <p>方向很关键：**适配器不依赖任何业务插件**（不声明 softdepend、不 shade 其 api），
 * 因此不会与「业务插件 → 适配器」的贡献方向形成插件依赖环。适配器每次请求动态查询
 * 扩展点，提供方的启停即时反映，无需重启。
 *
 * <p>授权与限额（全局开关 + 实例开关 + 玩家白名单 + 并发）由提供方负责，适配器不代其判断；
 * 失败抛 {@link YmclP2pException} 并带机器码，适配器据此给启动器可读原因（不伪造成功）。
 */
public interface YmclP2pProvider {

    /** 该提供方当前是否可用（面板开关、驱动配置等整体条件）。false 时适配器不宣告 p2p 能力。 */
    boolean available();

    /** 实例是否已开放 P2P（供启动器筛选「可直连」的服务器，不泄露白名单内容）。 */
    boolean instanceOpen(String instanceId);

    /**
     * 「服务器地址 → 实例」反查：启动器聚合服务器列表时把玩家看到的连接地址
     * （域名或 IP，可带端口）关联到后台实例。
     *
     * <p>只回**已开放 P2P** 的实例 ID；无匹配时返回 empty，适配器据此保留服务器原本的
     * 公网地址。不得抛出异常（可选能力探测不能拖垮列表端点），实现方内部自行降级。
     */
    Optional<String> instanceForAddress(String address);

    /**
     * 为玩家打开会话：签发票据并通知节点开面。
     *
     * @throws YmclP2pException 业务失败（code ∈ p2p.disabled / p2p.instance-disabled /
     *                         p2p.not-allowed / p2p.not-running / p2p.no-port / p2p.limit /
     *                         p2p.busy / p2p.node-unreachable / p2p.node-capability）
     */
    YmclP2pSessionView open(long userId, String instanceId);

    /** 中继本端候选并取回节点候选（TCP 直连路径可省略，打洞路径必用）。 */
    YmclP2pSessionView signal(long userId, String sessionId, List<YmclP2pCandidateView> candidates);

    /** 会话快照（不存在或不属于该玩家时返回 empty）。 */
    Optional<YmclP2pSessionView> session(long userId, String sessionId);

    /** 关闭会话（幂等）：通知节点回收监听与连接。 */
    void close(long userId, String sessionId, String reason);

    /** 当前活跃会话数（运维/测试观察点）。 */
    default int activeSessions() {
        return 0;
    }
}
