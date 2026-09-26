package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.ymcl.api.YmclP2pCandidateView;
import online.yudream.base.plugin.ymcl.api.YmclP2pException;
import online.yudream.base.plugin.ymcl.api.YmclP2pProvider;
import online.yudream.base.plugin.ymcl.api.YmclP2pSessionView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * P2P 直连能力的**聚合层**（YAP §6.12）：启动器无感接入实例直连。
 *
 * <p>玩家侧不出现任何 P2P 概念：启动器在「连接实例」时调本层开一个会话，拿到票据与节点候选，
 * 由随启动器分发的 sidecar（{@code mcpanel-p2p}）与节点完成握手与转发——面板不经手玩家流量。
 * 同一套会话机制可被后续能力复用（例如 P2P 语音：同样的票据 + 候选 + 打洞，换成 UDP 载荷）。
 *
 * <p>**方向**：适配器只聚合 {@link YmclP2pProvider} 扩展点，不依赖任何业务插件，因此不会
 * 与「业务插件 → 适配器」的贡献方向形成插件依赖环（缺失即整体降级）。
 */
public class YmclP2pLink {

    /** 提供方不可用（未安装 / 未启用 / P2P 未开启）：控制器统一降级 501。 */
    public static class Unavailable extends RuntimeException {
        private final String code;

        public Unavailable(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Supplier<List<YmclP2pProvider>> providers;

    /** 生产用法：每次调用动态查询扩展点（提供方启停即时反映，不缓存旧实例）。 */
    public static YmclP2pLink forContext(PluginContext context) {
        return new YmclP2pLink(() -> context.extensions(YmclP2pProvider.class));
    }

    /** 测试用法：直接注入提供方（可给多个以验证择优与降级）。 */
    public YmclP2pLink(Supplier<List<YmclP2pProvider>> providers) {
        this.providers = providers;
    }

    /** 是否有可用提供方（含其全局开关）。 */
    public boolean available() {
        return active().isPresent();
    }

    public Optional<String> unavailableReason() {
        if (registered().isEmpty()) {
            return Optional.of("未安装或未启用提供 P2P 直连的面板插件（mcpanel）");
        }
        if (active().isEmpty()) {
            return Optional.of("面板未开启 P2P 直连（面板设置 → 启动器 P2P 直连）");
        }
        return Optional.empty();
    }

    /** 实例是否已开放 P2P（供启动器标注「可直连」）。 */
    public boolean instanceOpen(String instanceId) {
        return active().map(provider -> provider.instanceOpen(instanceId)).orElse(false);
    }

    /**
     * 「服务器地址 → 实例」反查：服务器列表里标注可直连实例时使用。
     *
     * <p>没有提供方、提供方不可用、地址无匹配、提供方抛错，一律返回 empty——调用方据此
     * 保留服务器原本的公网地址，绝不因可选能力失败而报错。
     */
    public Optional<String> instanceForAddress(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        Optional<YmclP2pProvider> provider = active();
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        try {
            return provider.get().instanceForAddress(address);
        }
        catch (RuntimeException | LinkageError error) {
            return Optional.empty();
        }
    }

    /**
     * 开一个 P2P 会话（玩家点「连接」时调用，无感）。
     *
     * @return 启动器可直接使用的连接信息（snake_case）
     * @throws Unavailable 无可用提供方，或提供方报业务失败（code 原样透出）
     */
    public Map<String, Object> open(long userId, String instanceId) {
        YmclP2pProvider provider = require();
        return call(() -> toLauncher(provider.open(userId, instanceId), true));
    }

    /** 会话状态（启动器轮询：terminal=true 时停止隧道并提示原因）。 */
    public Optional<Map<String, Object>> session(long userId, String sessionId) {
        YmclP2pProvider provider = require();
        return call(() -> provider.session(userId, sessionId).map(view -> toLauncher(view, false)));
    }

    /** 关闭会话（启动器退出/切服；幂等）。 */
    public void close(long userId, String sessionId, String reason) {
        YmclP2pProvider provider = require();
        call(() -> {
            provider.close(userId, sessionId, reason);
            return null;
        });
    }

    /** 中继本端候选（打洞路径；TCP 直连可省略）。 */
    public Map<String, Object> signal(long userId, String sessionId, List<Map<String, Object>> candidates) {
        YmclP2pProvider provider = require();
        List<YmclP2pCandidateView> parsed = new ArrayList<>();
        if (candidates != null) {
            for (Map<String, Object> candidate : candidates) {
                parsed.add(new YmclP2pCandidateView(
                        text(candidate.get("proto"), "udp"),
                        text(candidate.get("host"), ""),
                        number(candidate.get("port")),
                        number(candidate.get("priority"))));
            }
        }
        return call(() -> toLauncher(provider.signal(userId, sessionId, parsed), false));
    }

    private YmclP2pProvider require() {
        return active().orElseThrow(() -> new Unavailable("p2p.upstream-unavailable",
                unavailableReason().orElse("P2P 直连不可用")));
    }

    /** 业务失败带上机器码；其余运行期异常/类链接问题诚实降级，不伪造成功。 */
    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        }
        catch (YmclP2pException error) {
            throw new Unavailable(error.code(), error.getMessage());
        }
        catch (RuntimeException | LinkageError error) {
            throw new Unavailable("p2p.upstream-error", "面板 P2P 服务调用失败：" + error);
        }
    }

    /**
     * 动态解析提供方：启停即时反映，不缓存实例（跨插件停用/重载后旧引用不可再用）。
     * 扩展点查询本身异常（开发模式类加载器不同源等）按「不可用」处理。
     */
    private List<YmclP2pProvider> registered() {
        try {
            List<YmclP2pProvider> list = providers.get();
            return list == null ? List.of() : list;
        }
        catch (RuntimeException | LinkageError error) {
            return List.of();
        }
    }

    /** 第一个自报可用的提供方。 */
    private Optional<YmclP2pProvider> active() {
        for (YmclP2pProvider provider : registered()) {
            if (provider.available()) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /** 契约快照 → 启动器载荷（YAP 习惯 snake_case；票据只在开会话响应里带出）。 */
    private static Map<String, Object> toLauncher(YmclP2pSessionView session, boolean withTicket) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", session.sessionId());
        payload.put("instance_id", session.instanceId());
        payload.put("node_id", session.nodeId());
        payload.put("state", session.state());
        payload.put("terminal", session.terminal());
        payload.put("target_port", session.targetPort());
        payload.put("rate_kbps", session.rateKbps());
        payload.put("expires_at", session.expiresAt());
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (YmclP2pCandidateView candidate : session.candidates()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("proto", candidate.proto());
            item.put("host", candidate.host());
            item.put("port", candidate.port());
            candidates.add(item);
        }
        payload.put("candidates", candidates);
        if (!session.reason().isBlank()) {
            payload.put("reason", session.reason());
        }
        if (withTicket) {
            payload.put("ticket", session.ticket());
        }
        return payload;
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
        }
        catch (NumberFormatException error) {
            return 0;
        }
    }
}
