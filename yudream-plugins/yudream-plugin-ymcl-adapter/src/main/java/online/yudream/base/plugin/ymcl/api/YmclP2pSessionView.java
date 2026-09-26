package online.yudream.base.plugin.ymcl.api;

import java.util.List;

/**
 * 会话快照（YAP §6.12）：启动器据此建隧道、轮询状态、回收。
 *
 * <p>{@code ticket} 只在开会话的响应里带出，后续查询为空——票据是玩家侧与节点握手的唯一凭据，
 * 不复用到状态轮询。{@code targetPort} 由提供方钉死，启动器无法指定目标。
 */
public record YmclP2pSessionView(
        String sessionId,
        String instanceId,
        String nodeId,
        String state,
        boolean terminal,
        String ticket,
        int targetPort,
        int rateKbps,
        long expiresAt,
        List<YmclP2pCandidateView> candidates,
        String reason) {

    public YmclP2pSessionView {
        sessionId = sessionId == null ? "" : sessionId;
        instanceId = instanceId == null ? "" : instanceId;
        nodeId = nodeId == null ? "" : nodeId;
        state = state == null ? "" : state;
        ticket = ticket == null ? "" : ticket;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        reason = reason == null ? "" : reason;
    }
}
