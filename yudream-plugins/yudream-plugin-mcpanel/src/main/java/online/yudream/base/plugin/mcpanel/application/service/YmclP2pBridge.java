package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.ymcl.api.YmclP2pCandidateView;
import online.yudream.base.plugin.ymcl.api.YmclP2pException;
import online.yudream.base.plugin.ymcl.api.YmclP2pProvider;
import online.yudream.base.plugin.ymcl.api.YmclP2pSessionView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 面板向启动器适配器（YMCL）贡献 P2P 直连能力：实现适配器的 {@link YmclP2pProvider}
 * 扩展点，由 {@code McpanelPlugin} 在启用时注册（ymcl-adapter 缺失即降级，面板自身不受影响）。
 *
 * <p>方向：**业务插件实现适配器声明的扩展点**，适配器只聚合。适配器因此不需要
 * softdepend mcpanel，也就不会与「business → adapter」的贡献方向成环（宿主会拒绝环上的
 * 可选依赖，导致能力静默失效）。
 *
 * <p>授权与限额仍全在 {@link P2PSessionService} 内（不因走适配器而放宽）；本类只做
 * 「内部会话 → 扩展点视图」的映射与错误码翻译。
 */
public class YmclP2pBridge implements YmclP2pProvider {

    private final P2PSessionService sessions;
    private final DomainService domains;

    public YmclP2pBridge(P2PSessionService sessions, DomainService domains) {
        this.sessions = sessions;
        this.domains = domains;
    }

    @Override
    public boolean available() {
        return sessions.enabled();
    }

    @Override
    public boolean instanceOpen(String instanceId) {
        return sessions.instanceOpen(instanceId);
    }

    /**
     * 地址反查交给域名服务（slug + 后缀的知识都在那一层），这里只做一层过滤：
     * 只广告**已开放 P2P** 的实例，否则启动器会为一个注定被拒的实例建隧道。
     * 反查不抛错（可选能力探测不能拖垮服务器列表端点）。
     */
    @Override
    public Optional<String> instanceForAddress(String address) {
        if (domains == null) {
            return Optional.empty();
        }
        try {
            return domains.instanceForHost(address)
                    .filter(McpanelInstance::p2pEnabled)
                    .map(McpanelInstance::id);
        }
        catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    @Override
    public YmclP2pSessionView open(long userId, String instanceId) {
        return call(() -> view(sessions.openSession(userId, instanceId), true));
    }

    @Override
    public YmclP2pSessionView signal(long userId, String sessionId, List<YmclP2pCandidateView> candidates) {
        List<Map<String, Object>> raw = new ArrayList<>();
        if (candidates != null) {
            for (YmclP2pCandidateView candidate : candidates) {
                raw.add(Map.of("proto", candidate.proto(),
                        "host", candidate.host(),
                        "port", candidate.port(),
                        "priority", candidate.priority()));
            }
        }
        return call(() -> view(sessions.signalSession(userId, sessionId, raw), false));
    }

    @Override
    public Optional<YmclP2pSessionView> session(long userId, String sessionId) {
        return sessions.findOwned(userId, sessionId).map(session -> view(session, false));
    }

    @Override
    public void close(long userId, String sessionId, String reason) {
        call(() -> {
            sessions.close(userId, sessionId, reason);
            return null;
        });
    }

    @Override
    public int activeSessions() {
        return sessions.sessionCount();
    }

    /** 内部会话 → 扩展点视图（票据只在开会话响应里带出）。 */
    private static YmclP2pSessionView view(P2PSessionService.Session session, boolean withTicket) {
        List<YmclP2pCandidateView> candidates = new ArrayList<>();
        for (Map<String, Object> candidate : session.nodeCandidates()) {
            candidates.add(new YmclP2pCandidateView(
                    String.valueOf(candidate.getOrDefault("proto", "tcp")),
                    String.valueOf(candidate.getOrDefault("host", "")),
                    asInt(candidate.get("port")),
                    asInt(candidate.get("priority"))));
        }
        return new YmclP2pSessionView(session.id(), session.instanceId(), session.nodeId(),
                session.state().name().toLowerCase(), terminal(session.state()), withTicket ? session.ticket() : "",
                session.targetPort(), session.rateKbps(), session.expiresAt(), candidates, session.reason());
    }

    /** 终态：启动器据此停止隧道并提示原因。 */
    private static boolean terminal(P2PSessionService.State state) {
        return state == P2PSessionService.State.FAILED
                || state == P2PSessionService.State.CLOSED
                || state == P2PSessionService.State.EXPIRED;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 错误码翻译：扩展点只暴露机器码与可读信息，不泄露内部异常类型。 */
    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        }
        catch (McpanelBusinessException error) {
            throw new YmclP2pException(error.code(), error.getMessage());
        }
    }
}
