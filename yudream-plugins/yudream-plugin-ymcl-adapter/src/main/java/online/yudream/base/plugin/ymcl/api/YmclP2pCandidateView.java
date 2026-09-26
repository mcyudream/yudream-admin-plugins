package online.yudream.base.plugin.ymcl.api;

/**
 * 一个可直连的节点候选（YAP §6.12）：relay 入口或打洞地址。
 *
 * <p>候选由提供方（面板 + 节点）生成并按 {@code priority} 升序排列，启动器（sidecar）
 * 依次尝试；候选里没有票据，票据与会话绑定。
 */
public record YmclP2pCandidateView(String proto, String host, int port, int priority) {

    public YmclP2pCandidateView {
        proto = proto == null || proto.isBlank() ? "tcp" : proto;
        host = host == null ? "" : host;
    }
}
