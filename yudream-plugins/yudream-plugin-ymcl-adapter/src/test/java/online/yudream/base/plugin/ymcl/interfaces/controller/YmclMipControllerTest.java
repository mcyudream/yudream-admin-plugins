package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.api.YmclP2pCandidateView;
import online.yudream.base.plugin.ymcl.api.YmclP2pProvider;
import online.yudream.base.plugin.ymcl.api.YmclP2pSessionView;
import online.yudream.base.plugin.ymcl.application.service.YmclP2pLink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务器列表里的直连标注：只有面板反查到实例、且该实例已开 P2P 时才写
 * {@code p2pInstanceId}；其余情况一律不写，服务器保持公网地址语义。
 */
class YmclMipControllerTest {

    /** 只实现反查的假 provider（其余方法不在本用例范围内）。 */
    private static final class FakeProvider implements YmclP2pProvider {
        final Map<String, String> byAddress = new LinkedHashMap<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean instanceOpen(String instanceId) {
            return true;
        }

        @Override
        public Optional<String> instanceForAddress(String address) {
            return Optional.ofNullable(byAddress.get(address));
        }

        @Override
        public YmclP2pSessionView open(long userId, String instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public YmclP2pSessionView signal(long userId, String sessionId,
                                         List<YmclP2pCandidateView> candidates) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<YmclP2pSessionView> session(long userId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public void close(long userId, String sessionId, String reason) {
        }

        @Override
        public int activeSessions() {
            return 0;
        }
    }

    private static Map<String, Object> server(String mcAddress, String... backupAddresses) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("serverId", "7");
        view.put("name", "生存服");
        view.put("mcAddress", mcAddress);
        if (backupAddresses.length > 0) {
            List<Map<String, Object>> endpoints = new ArrayList<>();
            endpoints.add(Map.of("address", mcAddress, "primary", true));
            for (String address : backupAddresses) {
                endpoints.add(Map.of("address", address, "primary", false));
            }
            view.put("endpoints", endpoints);
        }
        return view;
    }

    private static YmclP2pLink link(FakeProvider provider) {
        return new YmclP2pLink(() -> List.of(provider));
    }

    @Test
    void annotatesTheInstanceServingThePrimaryAddress() {
        FakeProvider provider = new FakeProvider();
        provider.byAddress.put("survival.mc.example.com", "inst-1");
        Map<String, Object> view = server("survival.mc.example.com");

        YmclMipController.annotateP2pInstance(link(provider), view);

        assertEquals("inst-1", view.get("p2pInstanceId"));
        assertEquals("survival.mc.example.com", view.get("mcAddress"), "公网地址保持不变");
    }

    @Test
    void fallsBackToBackupLineAddresses() {
        FakeProvider provider = new FakeProvider();
        provider.byAddress.put("backup.mc.example.com", "inst-9");
        Map<String, Object> view = server("1.2.3.4:25565", "backup.mc.example.com");

        YmclMipController.annotateP2pInstance(link(provider), view);

        assertEquals("inst-9", view.get("p2pInstanceId"));
    }

    @Test
    void leavesServerUnannotatedWhenNothingMatchesOrProviderIsMissing() {
        Map<String, Object> unknown = server("plain.example.com:25565");
        YmclMipController.annotateP2pInstance(link(new FakeProvider()), unknown);
        assertFalse(unknown.containsKey("p2pInstanceId"),
                "面板反查不到（未分配域名/实例未开 P2P）时不写字段");

        Map<String, Object> noProvider = server("survival.mc.example.com");
        YmclMipController.annotateP2pInstance(new YmclP2pLink(List::of), noProvider);
        assertFalse(noProvider.containsKey("p2pInstanceId"), "未装 mcpanel 时列表照常输出");

        assertTrue(unknown.containsKey("serverId"), "其余字段不受影响");
    }

    @Test
    void keepsAnAddresslessServerAsIs() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("serverId", "8");
        view.put("name", "未配置地址");
        YmclMipController.annotateP2pInstance(link(new FakeProvider()), view);
        assertFalse(view.containsKey("p2pInstanceId"));
    }
}
