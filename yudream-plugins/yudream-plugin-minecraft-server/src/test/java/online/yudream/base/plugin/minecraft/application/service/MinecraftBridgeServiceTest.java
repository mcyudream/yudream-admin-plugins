package online.yudream.base.plugin.minecraft.application.service;

import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServer;
import online.yudream.base.plugin.minecraft.domain.repo.MinecraftServerRepository;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftBridgeSettings;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageResult;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinecraftBridgeServiceTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String SERVER_ID = "srv-1";

    private MinecraftServerRepository repository;
    private PluginMessagingService messaging;
    private MinecraftBridgeService service;

    @BeforeEach
    void setUp() {
        repository = mock(MinecraftServerRepository.class);
        FrameworkServices framework = mock(FrameworkServices.class);
        messaging = mock(PluginMessagingService.class);
        when(framework.messaging()).thenReturn(messaging);
        when(messaging.sendToChannel(anyString(), anyString(), any(PluginMessageContent.class)))
                .thenReturn(CompletableFuture.completedStage(new PluginMessageResult(List.of(), true, false)));
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.id()).thenReturn(SERVER_ID);
        when(server.name()).thenReturn("生存服");
        when(repository.findById(SERVER_ID)).thenReturn(Optional.of(server));
        when(repository.list(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.of(server));
        service = new MinecraftBridgeService(repository, framework, serverId -> List.of(), () -> NOW);
    }

    private void givenSettings(MinecraftBridgeSettings settings) {
        when(repository.findBridgeSettings(SERVER_ID)).thenReturn(Optional.of(settings));
    }

    private MinecraftBridgeSettings settings(boolean enabled, boolean chat, boolean joinQuit, boolean death,
                                             boolean advancement, boolean toGame) {
        return settings(enabled, chat, joinQuit, death, advancement, false, toGame);
    }

    private MinecraftBridgeSettings settings(boolean enabled, boolean chat, boolean joinQuit, boolean death,
                                             boolean advancement, boolean startStop, boolean toGame) {
        return new MinecraftBridgeSettings(SERVER_ID, enabled, "conn-1", "grp-1", "测试群",
                chat, joinQuit, death, advancement, startStop, toGame, NOW);
    }

    private String capturedContent() {
        ArgumentCaptor<PluginMessageContent> captor = ArgumentCaptor.forClass(PluginMessageContent.class);
        verify(messaging, times(1)).sendToChannel(eq("conn-1"), eq("grp-1"), captor.capture());
        return captor.getValue().content();
    }

    @Test
    void joinMessageCarriesOnlineCountAndFirstThreeNames() {
        givenSettings(settings(true, false, true, false, false, false));
        service = new MinecraftBridgeService(repository, mockFrameworkWith(messaging),
                serverId -> List.of("Steve", "Alex", "Notch", "Herobrine"), () -> NOW);

        service.onPresence(SERVER_ID, true, "p-1", "Steve", NOW);

        String content = capturedContent();
        assertTrue(content.contains("▶ Steve 加入了服务器"));
        assertTrue(content.contains("当前在线 4 人：Steve、Alex、Notch 等"));
    }

    @Test
    void quitMessageSummarizesRemainingPlayers() {
        givenSettings(settings(true, false, true, false, false, false));
        service = new MinecraftBridgeService(repository, mockFrameworkWith(messaging),
                serverId -> List.of(), () -> NOW);

        service.onPresence(SERVER_ID, false, "p-1", "Steve", NOW);

        String content = capturedContent();
        assertTrue(content.contains("◀ Steve 退出了服务器"));
        assertTrue(content.contains("当前没有玩家在线"));
    }

    @Test
    void disabledSettingNeverForwards() {
        givenSettings(settings(false, true, true, true, true, true));

        service.onPresence(SERVER_ID, true, "p-1", "Steve", NOW);
        service.onGameEvent(SERVER_ID, MinecraftBridgeListener.GameEventKind.CHAT, "Steve", "你好", NOW);

        verify(messaging, never()).sendToChannel(anyString(), anyString(), any(PluginMessageContent.class));
    }

    @Test
    void eachForwardSwitchGuardsItsOwnEventKind() {
        givenSettings(settings(true, true, false, false, false, false));

        service.onGameEvent(SERVER_ID, MinecraftBridgeListener.GameEventKind.CHAT, "Steve", "你好", NOW);
        assertEquals("[生存服]:💬 Steve：你好", capturedContent());

        // 聊天开关打开但进退服开关关闭：进服事件不转发
        MinecraftBridgeService fresh = new MinecraftBridgeService(repository, mockFrameworkWith(messaging),
                serverId -> List.of("Steve"), () -> NOW);
        fresh.onPresence(SERVER_ID, true, "p-1", "Steve", NOW);
        verify(messaging, times(1)).sendToChannel(anyString(), anyString(), any(PluginMessageContent.class));
    }

    @Test
    void replayedOrStaleEventsAreNotForwardedTwice() {
        givenSettings(settings(true, false, true, false, false, false));
        service = new MinecraftBridgeService(repository, mockFrameworkWith(messaging),
                serverId -> List.of("Steve"), () -> NOW);

        service.onPresence(SERVER_ID, true, "p-1", "Steve", NOW);
        // 桥接至少一次投递：同一帧重放（同事件同时间戳）不重复进群
        service.onPresence(SERVER_ID, true, "p-1", "Steve", NOW);
        // 队列重放的过期事件（超过 5 分钟）不进群
        service.onPresence(SERVER_ID, true, "p-2", "Alex", NOW - MinecraftBridgeService.MAX_EVENT_AGE_MILLIS - 1);

        verify(messaging, times(1)).sendToChannel(anyString(), anyString(), any(PluginMessageContent.class));
    }

    @Test
    void inboundMessagesReachTheRightServerQueue() {
        givenSettings(settings(true, false, false, false, false, true));

        service.onGroupMessage(new PluginEvent("seq-1", "message_receive", "qq", "user-9", "grp-1",
                "大家好", null, null, null, null, null, "conn-1", "self-1", "m-1"));
        // 机器人自己的消息、指令消息、其他群消息都不进游戏
        service.onGroupMessage(new PluginEvent("seq-2", "message_receive", "qq", "self-1", "grp-1",
                "我自己", null, null, null, null, null, "conn-1", "self-1", "m-2"));
        service.onGroupMessage(new PluginEvent("seq-3", "message_receive", "qq", "user-9", "grp-1",
                "/服务器", null, null, null, null, null, "conn-1", "self-1", "m-3"));
        service.onGroupMessage(new PluginEvent("seq-4", "message_receive", "qq", "user-9", "grp-2",
                "别的群", null, null, null, null, null, "conn-1", "self-1", "m-4"));

        MinecraftBridgeService.InboundBatch batch = service.drainInbound(SERVER_ID, 0, 50);
        assertEquals(1, batch.messages().size());
        assertEquals("大家好", batch.messages().get(0).content());
        assertEquals("user-9", batch.messages().get(0).sender());
        assertEquals(1, batch.latest());

        // 游标推进后不重复拉取
        MinecraftBridgeService.InboundBatch second = service.drainInbound(SERVER_ID, 1, 50);
        assertTrue(second.messages().isEmpty());
    }

    @Test
    void saveSettingsRejectsEnabledWithoutTarget() {
        givenSettings(settings(true, false, false, false, false, false));
        MinecraftBridgeSettings invalid = new MinecraftBridgeSettings(SERVER_ID, true, "", "", "",
                false, false, false, false, false, false, 0L);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.saveSettings(invalid));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inboundStreamReplaysBufferThenPushesLive() {
        givenSettings(settings(true, false, false, false, false, true));
        service.onGroupMessage(new PluginEvent("s-1", "message_receive", "qq", "user-9", "grp-1",
                "缓冲消息", null, null, null, null, null, "conn-1", "self-1", "m-1"));

        PluginSseStream stream = service.openInboundStream(SERVER_ID, 0L);
        List<String> events = new java.util.ArrayList<>();
        List<Map<String, Object>> payloads = new java.util.ArrayList<>();
        List<Boolean> completed = new java.util.ArrayList<>();
        stream.subscribe(new PluginSseStream.Subscriber() {
            @Override
            public void send(String event, Object data) {
                events.add(event);
                payloads.add((Map<String, Object>) data);
            }

            @Override
            public void complete() {
                completed.add(true);
            }

            @Override
            public void error(Throwable throwable) {
            }
        });

        assertEquals("connected", events.get(0));
        assertEquals("message", events.get(1));
        assertEquals("缓冲消息", payloads.get(1).get("content"));
        assertEquals(1L, payloads.get(1).get("seq"));

        // 入队即实时推送
        service.onGroupMessage(new PluginEvent("s-2", "message_receive", "qq", "user-9", "grp-1",
                "实时消息", null, null, null, null, null, "conn-1", "self-1", "m-2"));
        assertEquals("message", events.get(2));
        assertEquals("实时消息", payloads.get(2).get("content"));
        assertEquals(2L, payloads.get(2).get("seq"));

        // 取消订阅后不再推送
        stream.unsubscribe(null);
        service.onGroupMessage(new PluginEvent("s-3", "message_receive", "qq", "user-9", "grp-1",
                "取消后的消息", null, null, null, null, null, "conn-1", "self-1", "m-3"));
        assertEquals(3, events.size());

        // 插件停用关闭订阅：complete 回调且不再泄漏线程
        service.openInboundStream(SERVER_ID, 0L);
        service.closeInboundStreams();
    }

    private FrameworkServices mockFrameworkWith(PluginMessagingService messagingService) {
        FrameworkServices framework = mock(FrameworkServices.class);
        when(framework.messaging()).thenReturn(messagingService);
        return framework;
    }
}
