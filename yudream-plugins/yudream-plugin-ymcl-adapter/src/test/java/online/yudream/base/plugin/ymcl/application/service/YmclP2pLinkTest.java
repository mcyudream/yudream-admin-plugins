package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.ymcl.api.YmclP2pCandidateView;
import online.yudream.base.plugin.ymcl.api.YmclP2pException;
import online.yudream.base.plugin.ymcl.api.YmclP2pProvider;
import online.yudream.base.plugin.ymcl.api.YmclP2pSessionView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器侧的 P2P 隔离层：玩家无感接入所需的载荷映射、票据只在开会话响应、
 * provider 缺失或业务失败时如实降级（启动器据此回退普通公网连接或提示原因）。
 */
class YmclP2pLinkTest {

    /** 假 provider：面板 P2P 服务的等价物。 */
    private static final class FakeProvider implements YmclP2pProvider {
        boolean available = true;
        boolean instanceOpen;
        RuntimeException failure;
        final Map<String, Optional<String>> addresses = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean instanceOpen(String instanceId) {
            return instanceOpen;
        }

        @Override
        public Optional<String> instanceForAddress(String address) {
            calls.add("address:" + address);
            if (failure != null) {
                throw failure;
            }
            return addresses.get(address);
        }

        @Override
        public YmclP2pSessionView open(long userId, String instanceId) {
            calls.add("open:" + userId + ":" + instanceId);
            if (failure != null) {
                throw failure;
            }
            return new YmclP2pSessionView("sess-1", instanceId, "node-1", "waiting", false,
                    "ticket-abc", 25600, 2048, 1_790_000_000_000L,
                    List.of(new YmclP2pCandidateView("tcp", "203.0.113.9", 41234, 10)), "");
        }

        @Override
        public YmclP2pSessionView signal(long userId, String sessionId,
                                         List<YmclP2pCandidateView> candidates) {
            calls.add("signal:" + sessionId + ":" + candidates.size());
            return new YmclP2pSessionView("sess-1", "inst-1", "node-1", "connecting", false,
                    "ticket-abc", 25600, 2048, 1_790_000_000_000L,
                    List.of(new YmclP2pCandidateView("tcp", "203.0.113.9", 41234, 10)), "");
        }

        @Override
        public Optional<YmclP2pSessionView> session(long userId, String sessionId) {
            calls.add("session:" + sessionId);
            return Optional.of(new YmclP2pSessionView(sessionId, "inst-1", "node-1", "direct", false,
                    "ticket-abc", 25600, 2048, 1_790_000_000_000L,
                    List.of(new YmclP2pCandidateView("tcp", "203.0.113.9", 41234, 10)), ""));
        }

        @Override
        public void close(long userId, String sessionId, String reason) {
            calls.add("close:" + sessionId + ":" + reason);
        }

        @Override
        public int activeSessions() {
            return 1;
        }
    }

    private static YmclP2pLink link(FakeProvider provider) {
        return new YmclP2pLink(() -> provider == null ? List.of() : List.of(provider));
    }

    @Test
    void openReturnsLauncherReadyPayloadWithTicket() {
        FakeProvider provider = new FakeProvider();
        Map<String, Object> payload = link(provider).open(1001L, "inst-1");

        assertEquals("sess-1", payload.get("session_id"));
        assertEquals("inst-1", payload.get("instance_id"));
        assertEquals("ticket-abc", payload.get("ticket"), "开会话必须带票据（sidecar 据此握手）");
        assertEquals(25600, payload.get("target_port"));
        assertEquals(2048, payload.get("rate_kbps"));
        assertEquals(false, payload.get("terminal"));
        List<?> candidates = (List<?>) payload.get("candidates");
        assertEquals(1, candidates.size());
        Map<?, ?> candidate = (Map<?, ?>) candidates.get(0);
        assertEquals("203.0.113.9", candidate.get("host"));
        assertEquals(41234, candidate.get("port"));
        assertEquals(List.of("open:1001:inst-1"), provider.calls);
    }

    @Test
    void statusAndCloseUseSnakeCaseAndHideTicket() {
        FakeProvider provider = new FakeProvider();
        YmclP2pLink link = link(provider);

        Map<String, Object> status = link.session(1001L, "sess-1").orElseThrow();
        assertEquals("direct", status.get("state"));
        assertFalse(status.containsKey("ticket"), "非开会话响应不得回传票据");

        link.close(1001L, "sess-1", "启动器退出");
        assertEquals("close:sess-1:启动器退出", provider.calls.get(1));
    }

    @Test
    void providerBusinessFailuresCarryCodesToLauncher() {
        FakeProvider provider = new FakeProvider();
        provider.failure = new YmclP2pException("p2p.instance-disabled", "该实例未开启 P2P 接入");
        YmclP2pLink.Unavailable error = assertThrows(YmclP2pLink.Unavailable.class,
                () -> link(provider).open(1001L, "inst-1"));
        assertEquals("p2p.instance-disabled", error.code());
        assertEquals("该实例未开启 P2P 接入", error.getMessage());
    }

    @Test
    void missingOrDisabledProviderDegradesHonestly() {
        // 没有提供方（面板插件未安装/未启用）：解析为空
        YmclP2pLink none = new YmclP2pLink(List::of);
        assertFalse(none.available());
        assertTrue(none.unavailableReason().orElse("").contains("mcpanel"),
                "原因要能指向缺失的插件，便于排障");
        YmclP2pLink.Unavailable missing = assertThrows(YmclP2pLink.Unavailable.class,
                () -> none.open(1001L, "inst-1"));
        assertEquals("p2p.upstream-unavailable", missing.code());

        // 面板装了但没开 P2P
        FakeProvider disabled = new FakeProvider();
        disabled.available = false;
        YmclP2pLink link = link(disabled);
        assertFalse(link.available());
        assertTrue(link.unavailableReason().orElse("").contains("未开启"));
    }

    @Test
    void picksTheFirstAvailableProvider() {
        FakeProvider offline = new FakeProvider();
        offline.available = false;
        FakeProvider ready = new FakeProvider();
        YmclP2pLink link = new YmclP2pLink(() -> List.of(offline, ready));

        assertTrue(link.available(), "不可用的提供方不阻塞后续提供方");
        link.open(1001L, "inst-1");
        assertEquals(List.of(), offline.calls, "跳过不可用的提供方");
        assertEquals(List.of("open:1001:inst-1"), ready.calls);
    }

    @Test
    void linkageErrorFromProviderIsTreatedAsUnavailable() {
        YmclP2pLink link = new YmclP2pLink(() -> {
            throw new NoClassDefFoundError("online/yudream/base/plugin/ymcl/api/YmclP2pProvider");
        });
        assertFalse(link.available());
        assertEquals("p2p.upstream-unavailable",
                assertThrows(YmclP2pLink.Unavailable.class, () -> link.open(1001L, "x")).code());
    }

    @Test
    void signalRelaysCandidatesInBothDirections() {
        FakeProvider provider = new FakeProvider();
        Map<String, Object> payload = link(provider).signal(1001L, "sess-1", List.of(
                Map.of("proto", "udp", "host", "198.51.100.7", "port", 52100)));

        assertEquals("signal:sess-1:1", provider.calls.get(0));
        assertEquals("connecting", payload.get("state"));
        List<?> candidates = (List<?>) payload.get("candidates");
        Map<?, ?> candidate = (Map<?, ?>) new LinkedHashMap<>((Map<?, ?>) candidates.get(0));
        assertEquals("203.0.113.9", candidate.get("host"), "节点候选回给启动器（打洞互见）");
    }

    @Test
    void resolvesServerAddressToInstanceButDegradesSilently() {
        FakeProvider provider = new FakeProvider();
        provider.addresses.put("survival.mc.example.com", Optional.of("inst-1"));
        provider.addresses.put("creative.mc.example.com", Optional.empty());
        YmclP2pLink link = new YmclP2pLink(() -> List.of(provider));

        assertEquals(Optional.of("inst-1"), link.instanceForAddress("survival.mc.example.com"));
        assertEquals(Optional.empty(), link.instanceForAddress("creative.mc.example.com"),
                "面板反查不到时保持 empty（服务器继续用公网地址）");
        assertEquals(Optional.empty(), link.instanceForAddress("  "));

        // 面板整体未开 P2P：不再问 provider（否则启动器会拿到注定被拒的实例）
        provider.available = false;
        int callsWhileAvailable = provider.calls.size();
        assertEquals(Optional.empty(), link.instanceForAddress("survival.mc.example.com"));
        assertEquals(callsWhileAvailable, provider.calls.size(), "不可用时不得继续反查");

        // provider 抛错（未安装/契约错配）也必须是 empty，不能把异常抛给列表端点
        provider.available = true;
        provider.failure = new IllegalStateException("panel gone");
        assertEquals(Optional.empty(), link.instanceForAddress("survival.mc.example.com"));

        // provider 整体缺失
        YmclP2pLink missing = new YmclP2pLink(List::of);
        assertEquals(Optional.empty(), missing.instanceForAddress("survival.mc.example.com"));
        assertFalse(missing.available());
    }
}
