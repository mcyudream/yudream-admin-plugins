package online.yudream.base.plugin.minecraft.application.service;

import online.yudream.base.plugin.minecraft.api.PluginMinecraftOnlineWindow;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftPlayerActivity;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftSubServer;
import online.yudream.base.plugin.minecraft.api.PluginMinecraftSubServerActivity;
import online.yudream.base.plugin.minecraft.application.cmd.MinecraftPlayerEventCmd;
import online.yudream.base.plugin.minecraft.application.cmd.MinecraftPlayerSnapshotCmd;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivity;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivityEvent;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServer;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServerTopology;
import online.yudream.base.plugin.minecraft.domain.repo.MinecraftServerRepository;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerEndpoint;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerMap;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerSeason;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServer;
import online.yudream.base.plugin.minecraft.infrastructure.service.MinecraftStatusService;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinecraftServerAppServiceTest {

    private static final long BASE = 1_700_000_000_000L;
    private MinecraftServerRepository repository;
    private PluginFileStore files;
    private MinecraftServerAppService service;

    @BeforeEach
    void setUp() {
        repository = mock(MinecraftServerRepository.class);
        files = mock(PluginFileStore.class);
        PluginContext context = mock(PluginContext.class);
        when(context.framework()).thenReturn(mock(FrameworkServices.class));
        when(context.files()).thenReturn(files);
        when(repository.findById("server-1")).thenReturn(Optional.of(mock(MinecraftServer.class)));
        service = new MinecraftServerAppService(repository, mock(MinecraftStatusService.class), context, files);
    }

    @Test
    void snapshotReopensPlayerWhoseQuitWasRecordedIncorrectly() {
        MinecraftPlayerActivity offline = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)
                .join("Steve", BASE)
                .quit("Steve", BASE + 1_000);
        when(repository.listPlayerActivities("server-1", 1, 200)).thenReturn(List.of(offline));

        int online = service.reconcilePlayerSnapshot("server-1", snapshot(BASE + 2_000, "player-1"));

        assertEquals(1, online);
        ArgumentCaptor<MinecraftPlayerActivity> saved = ArgumentCaptor.forClass(MinecraftPlayerActivity.class);
        verify(repository).savePlayerActivity(saved.capture());
        assertEquals(BASE + 2_000, saved.getValue().currentOnlineSince());
    }

    @Test
    void snapshotClosesPlayerMissingFromAuthoritativeRoster() {
        MinecraftPlayerActivity online = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)
                .join("Steve", BASE);
        when(repository.listPlayerActivities("server-1", 1, 200)).thenReturn(List.of(online));

        service.reconcilePlayerSnapshot("server-1", new MinecraftPlayerSnapshotCmd(BASE + 3_000, List.of()));

        ArgumentCaptor<MinecraftPlayerActivity> saved = ArgumentCaptor.forClass(MinecraftPlayerActivity.class);
        ArgumentCaptor<MinecraftPlayerActivityEvent> event = ArgumentCaptor.forClass(MinecraftPlayerActivityEvent.class);
        verify(repository).savePlayerActivity(saved.capture());
        verify(repository).savePlayerActivityEvent(event.capture());
        assertFalse(saved.getValue().online());
        assertEquals(3_000, saved.getValue().totalOnlineMillis());
        assertEquals(MinecraftPlayerActivityEvent.Type.SERVER_SNAPSHOT, event.getValue().type());
    }

    @Test
    void delayedSnapshotCannotCloseNewerSession() {
        MinecraftPlayerActivity online = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE + 4_000)
                .join("Steve", BASE + 4_000);
        when(repository.listPlayerActivities("server-1", 1, 200)).thenReturn(List.of(online));

        service.reconcilePlayerSnapshot("server-1", new MinecraftPlayerSnapshotCmd(BASE + 3_000, List.of()));

        verify(repository, never()).savePlayerActivity(any());
        verify(repository, never()).savePlayerActivityEvent(any());
    }

    // ------------------------------------------------------------------ 子服维度

    @Test
    void perSubServerEventsAccumulateSeparatelyAndTheTotalIsTheirSum() {
        Map<String, MinecraftPlayerActivity> store = statefulActivityStore();

        service.recordJoin("server-1", event("player-1", "Steve", BASE, "fabric"));
        service.recordQuit("server-1", event("player-1", "Steve", BASE + 60_000, "fabric"));
        service.recordJoin("server-1", event("player-1", "Steve", BASE + 60_000, "paper"));
        service.recordQuit("server-1", event("player-1", "Steve", BASE + 90_000, "paper"));

        MinecraftPlayerActivity saved = store.get("server-1:player-1");
        assertEquals(60_000, saved.subServers().get("fabric").onlineMillis());
        assertEquals(30_000, saved.subServers().get("paper").onlineMillis());
        assertEquals(90_000, saved.totalOnlineMillis());
        assertFalse(saved.online());
    }

    @Test
    void legacyEventsWithoutASubServerStillLandInTheDefaultBucket() {
        Map<String, MinecraftPlayerActivity> store = statefulActivityStore();

        service.recordJoin("server-1", new MinecraftPlayerEventCmd("player-1", "Steve", BASE));
        service.recordQuit("server-1", new MinecraftPlayerEventCmd("player-1", "Steve", BASE + 5_000));

        MinecraftPlayerActivity saved = store.get("server-1:player-1");
        assertEquals(1, saved.subServers().size());
        assertTrue(saved.subServers().containsKey("default"));
        assertEquals(5_000, saved.totalOnlineMillis());
    }

    @Test
    void groupedSnapshotDoesNotClosePlayersOnSubServersItDoesNotList() {
        MinecraftPlayerActivity online = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)
                .join("fabric", "Steve", BASE);
        when(repository.listPlayerActivities("server-1", 1, 200)).thenReturn(List.of(online));

        int onlinePlayers = service.reconcilePlayerSnapshot("server-1",
                grouped(BASE + 3_000, roster("paper")));

        assertEquals(0, onlinePlayers);
        verify(repository, never()).savePlayerActivity(any());
        verify(repository, never()).savePlayerActivityEvent(any());
    }

    @Test
    void groupedSnapshotClosesOnlyTheListedSubServerItFindsEmpty() {
        MinecraftPlayerActivity online = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)
                .join("fabric", "Steve", BASE)
                .join("paper", "Steve", BASE + 1_000);
        when(repository.listPlayerActivities("server-1", 1, 200)).thenReturn(List.of(online));

        service.reconcilePlayerSnapshot("server-1", grouped(BASE + 3_000, roster("fabric")));

        ArgumentCaptor<MinecraftPlayerActivity> saved = ArgumentCaptor.forClass(MinecraftPlayerActivity.class);
        verify(repository).savePlayerActivity(saved.capture());
        MinecraftPlayerActivity value = saved.getValue();
        assertFalse(value.subServers().get("fabric").online());
        assertTrue(value.subServers().get("paper").online());
        assertTrue(value.online());
        assertEquals(3_000, value.subServers().get("fabric").onlineMillis());
        assertEquals(0, value.subServers().get("paper").onlineMillis());
        assertEquals(3_000, value.totalOnlineMillis());
    }

    @Test
    void groupedSnapshotSavesTheActivityOnceAndRecordsOneEventPerClosedSubServer() {
        MinecraftPlayerActivity online = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)
                .join("fabric", "Steve", BASE)
                .join("paper", "Steve", BASE + 1_000);
        when(repository.listPlayerActivities("server-1", 1, 200)).thenReturn(List.of(online));

        service.reconcilePlayerSnapshot("server-1", grouped(BASE + 5_000, roster("fabric"), roster("paper")));

        ArgumentCaptor<MinecraftPlayerActivity> saved = ArgumentCaptor.forClass(MinecraftPlayerActivity.class);
        verify(repository).savePlayerActivity(saved.capture());
        assertFalse(saved.getValue().online());
        assertEquals(5_000 + 4_000, saved.getValue().totalOnlineMillis());

        // 收尾事件按子服逐条写。一条不分子服的收尾事件也能参与任意子服的回放，但那样就分不清关掉的
        // 是哪台子服，回放「fabric 这段待了多久」时会算错。
        ArgumentCaptor<MinecraftPlayerActivityEvent> events =
                ArgumentCaptor.forClass(MinecraftPlayerActivityEvent.class);
        verify(repository, times(2)).savePlayerActivityEvent(events.capture());
        assertEquals(List.of("fabric", "paper"), events.getAllValues().stream()
                .map(MinecraftPlayerActivityEvent::subServer).sorted().toList());
        assertTrue(events.getAllValues().stream()
                .allMatch(event -> event.type() == MinecraftPlayerActivityEvent.Type.SERVER_SNAPSHOT));
    }

    @Test
    void groupedSnapshotReopensAListedSubServer() {
        MinecraftPlayerActivity offline = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)
                .join("fabric", "Steve", BASE)
                .quit("fabric", "Steve", BASE + 1_000);
        when(repository.listPlayerActivities("server-1", 1, 200)).thenReturn(List.of(offline));
        when(repository.findPlayerActivity("server-1", "player-1")).thenReturn(Optional.of(offline));

        int onlinePlayers = service.reconcilePlayerSnapshot("server-1",
                grouped(BASE + 2_000, roster("fabric", "player-1")));

        assertEquals(1, onlinePlayers);
        ArgumentCaptor<MinecraftPlayerActivity> saved = ArgumentCaptor.forClass(MinecraftPlayerActivity.class);
        verify(repository).savePlayerActivity(saved.capture());
        assertTrue(saved.getValue().subServers().get("fabric").online());
        assertEquals(BASE + 2_000, saved.getValue().subServers().get("fabric").currentOnlineSince());
    }

    @Test
    void flatSnapshotStillCoversTheWholeServerIncludingEverySubServer() {
        // 旧形态没有子服维度：名册缺失即整服离线，所有子服一起收尾。
        MinecraftPlayerActivity online = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)
                .join("fabric", "Steve", BASE)
                .join("paper", "Steve", BASE + 1_000);
        when(repository.listPlayerActivities("server-1", 1, 200)).thenReturn(List.of(online));

        service.reconcilePlayerSnapshot("server-1", new MinecraftPlayerSnapshotCmd(BASE + 5_000, List.of()));

        ArgumentCaptor<MinecraftPlayerActivity> saved = ArgumentCaptor.forClass(MinecraftPlayerActivity.class);
        verify(repository).savePlayerActivity(saved.capture());
        assertFalse(saved.getValue().online());
        assertEquals(9_000, saved.getValue().totalOnlineMillis());
    }

    @Test
    void flatSnapshotTreatsAnEmptyServersArrayAsNoSubServerDimension() {
        MinecraftPlayerActivity online = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)
                .join("fabric", "Steve", BASE);
        when(repository.listPlayerActivities("server-1", 1, 200)).thenReturn(List.of(online));

        // servers 为空数组等价于没有子服维度：沿用扁平语义。
        service.reconcilePlayerSnapshot("server-1",
                new MinecraftPlayerSnapshotCmd(BASE + 3_000, List.of(), List.of()));

        ArgumentCaptor<MinecraftPlayerActivity> saved = ArgumentCaptor.forClass(MinecraftPlayerActivity.class);
        verify(repository).savePlayerActivity(saved.capture());
        assertFalse(saved.getValue().online());
    }

    @Test
    void crossPluginBreakdownReadsEverySubServer() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)
                .join("fabric", "Steve", BASE)
                .quit("fabric", "Steve", BASE + 60_000)
                .join("paper", "Steve", BASE + 60_000)
                .quit("paper", "Steve", BASE + 90_000);
        when(repository.findPlayerActivity("server-1", "player-1")).thenReturn(Optional.of(activity));
        when(repository.listPlayerActivities("server-1", 1, 20)).thenReturn(List.of(activity));
        when(repository.countPlayerActivities("server-1")).thenReturn(1L);

        List<PluginMinecraftSubServerActivity> breakdown = service.minecraftSubServerActivities("server-1", "player-1");

        assertEquals(2, breakdown.size());
        assertEquals(60_000, breakdown.stream().filter(item -> "fabric".equals(item.subServer()))
                .findFirst().orElseThrow().totalOnlineMillis());
        assertEquals(30_000, breakdown.stream().filter(item -> "paper".equals(item.subServer()))
                .findFirst().orElseThrow().totalOnlineMillis());
        // 既有查询仍然返回跨服合计，签名与语义都没变。
        List<PluginMinecraftPlayerActivity> aggregate = service.minecraftPlayerActivities("server-1", 1, 20);
        assertEquals(1, aggregate.size());
        assertEquals(90_000, aggregate.getFirst().totalOnlineMillis());
    }

    // ------------------------------------------------------------------ 地图（既有用例）

    @Test
    void saveMapLinkDeletesStoredZipAndPersistsExternalUrl() {
        MinecraftServer existing = serverWithMap(MinecraftServerMap.storedFile("file-1", "servers/server-1/map.zip", "file-1.zip", true));
        when(repository.findById("server-1")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findStatus("server-1")).thenReturn(Optional.empty());

        MinecraftServerDTO saved = service.saveMapLink("server-1", "https://pan.example/s/abc", "S1 存档");

        verify(files).delete("servers/server-1/map.zip");
        ArgumentCaptor<MinecraftServer> persisted = ArgumentCaptor.forClass(MinecraftServer.class);
        verify(repository).save(persisted.capture());
        assertEquals("https://pan.example/s/abc", persisted.getValue().map().externalUrl());
        assertFalse(persisted.getValue().map().publicAccess());
        assertEquals("S1 存档", saved.map().originalName());
        assertEquals("https://pan.example/s/abc", saved.map().externalUrl());
    }

    @Test
    void userDetailHidesPrivateExternalUrl() {
        MinecraftServer existing = serverWithMap(MinecraftServerMap.externalLink("https://pan.example/s/secret", "私有存档", false));
        when(repository.findById("server-1")).thenReturn(Optional.of(existing));
        when(repository.findStatus("server-1")).thenReturn(Optional.empty());

        MinecraftServerDTO dto = service.userDetail("server-1", false);

        assertNull(dto.map().externalUrl());
        assertFalse(dto.map().publicAccess());
        assertEquals("私有存档", dto.map().originalName());
    }

    @Test
    void userDetailKeepsPublicExternalUrl() {
        MinecraftServer existing = serverWithMap(MinecraftServerMap.externalLink("https://pan.example/s/abc", "公开存档", true));
        when(repository.findById("server-1")).thenReturn(Optional.of(existing));
        when(repository.findStatus("server-1")).thenReturn(Optional.empty());

        MinecraftServerDTO dto = service.userDetail("server-1", false);

        assertEquals("https://pan.example/s/abc", dto.map().externalUrl());
    }

    @Test
    void downloadMapRejectsExternalLink() {
        MinecraftServer existing = serverWithMap(MinecraftServerMap.externalLink("https://pan.example/s/abc", "公开存档", true));
        when(repository.findById("server-1")).thenReturn(Optional.of(existing));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.downloadMap("server-1", true, true));
        assertEquals("请使用网盘链接下载", error.getMessage());
        verify(files, never()).get(any());
    }

    // ------------------------------------------------------------------ 按子服的时间窗

    /** 一次 fabric -> paper 的换服：四段事件，两个互不重叠的区间。 */
    private void stubFabricThenPaperSession(String playerId) {
        when(repository.findPlayerActivity("server-1", playerId))
                .thenReturn(Optional.of(MinecraftPlayerActivity.empty("server-1", playerId, "Steve", BASE)));
        when(repository.listPlayerActivityEvents(eq("server-1"), eq(playerId), anyInt(), anyInt()))
                .thenReturn(List.of(
                        MinecraftPlayerActivityEvent.create("server-1", playerId, "Steve", "fabric",
                                MinecraftPlayerActivityEvent.Type.JOIN, BASE + 10_000),
                        MinecraftPlayerActivityEvent.create("server-1", playerId, "Steve", "fabric",
                                MinecraftPlayerActivityEvent.Type.QUIT, BASE + 40_000),
                        MinecraftPlayerActivityEvent.create("server-1", playerId, "Steve", "paper",
                                MinecraftPlayerActivityEvent.Type.JOIN, BASE + 60_000),
                        MinecraftPlayerActivityEvent.create("server-1", playerId, "Steve", "paper",
                                MinecraftPlayerActivityEvent.Type.QUIT, BASE + 80_000)));
    }

    @Test
    void onlineWindowWithoutASubServerCountsEverySubServer() {
        stubFabricThenPaperSession("player-1");

        PluginMinecraftOnlineWindow window = service
                .minecraftOnlineWindow("server-1", "player-1", BASE, BASE + 100_000)
                .orElseThrow();

        assertEquals(30_000 + 20_000, window.onlineMillis());
    }

    @Test
    void onlineWindowScopedToOneSubServerIgnoresTheOthers() {
        stubFabricThenPaperSession("player-1");

        PluginMinecraftOnlineWindow fabric = service
                .minecraftOnlineWindow("server-1", "player-1", "fabric", BASE, BASE + 100_000)
                .orElseThrow();
        PluginMinecraftOnlineWindow paper = service
                .minecraftOnlineWindow("server-1", "player-1", "paper", BASE, BASE + 100_000)
                .orElseThrow();

        assertEquals(30_000, fabric.onlineMillis());
        assertEquals(20_000, paper.onlineMillis());
    }

    @Test
    void onlineWindowScopedToOneSubServerClipsToTheSubServerInterval() {
        stubFabricThenPaperSession("player-1");

        // 窗口只覆盖 paper 那一段的一半：fabric 的区间在窗口开始前就结束了，不计入。
        PluginMinecraftOnlineWindow paper = service
                .minecraftOnlineWindow("server-1", "player-1", "paper", BASE + 60_000, BASE + 70_000)
                .orElseThrow();

        assertEquals(10_000, paper.onlineMillis());
    }

    /**
     * 整服级别的收尾（没有子服维度）必须能关掉某个子服上开着的区间。
     *
     * <p>否则一次服务端离线就会让该子服的区间一直开到窗口末尾，时长被严重高估。
     */
    @Test
    void aWholeServerCloseStillClosesAnOpenSubServerInterval() {
        when(repository.findPlayerActivity("server-1", "player-1"))
                .thenReturn(Optional.of(MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)));
        when(repository.listPlayerActivityEvents(eq("server-1"), eq("player-1"), anyInt(), anyInt()))
                .thenReturn(List.of(
                        MinecraftPlayerActivityEvent.create("server-1", "player-1", "Steve", "fabric",
                                MinecraftPlayerActivityEvent.Type.JOIN, BASE + 10_000),
                        // 服务端离线：整服事件，不指明子服。
                        MinecraftPlayerActivityEvent.create("server-1", "player-1", "Steve",
                                MinecraftPlayerActivityEvent.Type.SERVER_OFFLINE, BASE + 25_000)));

        PluginMinecraftOnlineWindow fabric = service
                .minecraftOnlineWindow("server-1", "player-1", "fabric", BASE, BASE + 600_000)
                .orElseThrow();

        assertEquals(15_000, fabric.onlineMillis());
    }

    /**
     * 升级期间同一次会话的 JOIN 与 QUIT 可能一个没有子服字段、一个带子服名。
     *
     * <p>这是本机真实发生过的形态：本次发布之前写入的 JOIN 丢了子服，本次发布写入的 QUIT 带着
     * fabric。区间归属按整段决定——优先用开启事件的名字，开启事件为空时退回收尾事件的名字，因此这段
     * 会话应算在 fabric 上、不算在 paper 上。
     */
    @Test
    void aMixedIntervalIsAttributedToTheClosingEventWhenTheOpeningEventHasNoSubServer() {
        when(repository.findPlayerActivity("server-1", "player-1"))
                .thenReturn(Optional.of(MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)));
        when(repository.listPlayerActivityEvents(eq("server-1"), eq("player-1"), anyInt(), anyInt()))
                .thenReturn(List.of(
                        // 本次发布之前写入：开启事件没有子服维度。
                        MinecraftPlayerActivityEvent.create("server-1", "player-1", "Steve",
                                MinecraftPlayerActivityEvent.Type.JOIN, BASE + 10_000),
                        // 本次发布写入：收尾事件带着子服名。
                        MinecraftPlayerActivityEvent.create("server-1", "player-1", "Steve", "fabric",
                                MinecraftPlayerActivityEvent.Type.QUIT, BASE + 70_000)));

        long fabric = service.minecraftOnlineWindow("server-1", "player-1", "fabric", BASE, BASE + 600_000)
                .orElseThrow().onlineMillis();
        long paper = service.minecraftOnlineWindow("server-1", "player-1", "paper", BASE, BASE + 600_000)
                .orElseThrow().onlineMillis();

        assertEquals(60_000, fabric);
        assertEquals(0, paper);
    }

    /**
     * 活动时段还在进行时（windowEnd 在未来），此刻仍在线的人不能被记为「在线满整个周期」。
     *
     * <p>曾经的算法把没有收尾的开放区间一路算到 windowEnd，于是刚进服的人也会凑够阈值；本机
     * 实测出现过把 1 分钟的子服算成 7550 分钟。
     */
    @Test
    void anOpenIntervalIsNeverCountedPastNow() {
        long now = System.currentTimeMillis();
        when(repository.findPlayerActivity("server-1", "player-1"))
                .thenReturn(Optional.of(MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", now)));
        when(repository.listPlayerActivityEvents(eq("server-1"), eq("player-1"), anyInt(), anyInt()))
                .thenReturn(List.of(
                        MinecraftPlayerActivityEvent.create("server-1", "player-1", "Steve", "fabric",
                                MinecraftPlayerActivityEvent.Type.JOIN, now - 60_000)));

        // 窗口从很久以前一直开到 10 天之后，且没有任何收尾事件。
        PluginMinecraftOnlineWindow window = service
                .minecraftOnlineWindow("server-1", "player-1", "fabric", now - 600_000, now + 10L * 86_400_000L)
                .orElseThrow();

        // 只能算到「现在」，容差 5 秒。
        assertTrue(window.onlineMillis() >= 55_000 && window.onlineMillis() <= 65_000,
                "期望约 60 秒，实际 " + window.onlineMillis() + " ms");
    }

    /** 开启与收尾都没有子服名的区间无法归属，任何具名子服都不计入，但整服口径照常计入。 */
    @Test
    void anIntervalWithNoSubServerOnEitherEndIsOnlyCountedForTheWholeServer() {
        when(repository.findPlayerActivity("server-1", "player-1"))
                .thenReturn(Optional.of(MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", BASE)));
        when(repository.listPlayerActivityEvents(eq("server-1"), eq("player-1"), anyInt(), anyInt()))
                .thenReturn(List.of(
                        MinecraftPlayerActivityEvent.create("server-1", "player-1", "Steve",
                                MinecraftPlayerActivityEvent.Type.JOIN, BASE + 10_000),
                        MinecraftPlayerActivityEvent.create("server-1", "player-1", "Steve",
                                MinecraftPlayerActivityEvent.Type.QUIT, BASE + 40_000)));

        assertEquals(30_000, service.minecraftOnlineWindow("server-1", "player-1", BASE, BASE + 600_000)
                .orElseThrow().onlineMillis());
        assertEquals(0, service.minecraftOnlineWindow("server-1", "player-1", "fabric", BASE, BASE + 600_000)
                .orElseThrow().onlineMillis());
    }

    // ------------------------------------------------------------------ 子服列表

    @Test
    void subServersComeFromTheReportedTopology() {
        when(repository.findTopology("server-1")).thenReturn(Optional.of(new MinecraftServerTopology(
                "server-1", "velocity", "3.5.0", BASE + 1_000,
                List.of(new MinecraftSubServer("fabric", "/127.0.0.1:25566", 1, true, true, 0),
                        new MinecraftSubServer("paper", "/127.0.0.1:25567", 0, true, false, 1)))));

        List<PluginMinecraftSubServer> subServers = service.minecraftSubServers("server-1");

        assertEquals(List.of("fabric", "paper"), subServers.stream().map(PluginMinecraftSubServer::name).toList());
        assertTrue(subServers.get(0).defaultServer());
        assertFalse(subServers.get(1).defaultServer());
        assertTrue(subServers.get(0).sensor());
    }

    /** 单机服（独立 Fabric / Bukkit）没有拓扑，界面据此不显示子服选择。 */
    @Test
    void subServersAreEmptyWhenThereIsNoTopology() {
        when(repository.findTopology("server-1")).thenReturn(Optional.empty());

        assertTrue(service.minecraftSubServers("server-1").isEmpty());
        assertTrue(service.minecraftSubServers("").isEmpty());
        assertTrue(service.minecraftSubServers(null).isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    /** 让 mock 仓储记住写入的记录，便于断言跨事件累计的结果。 */
    private Map<String, MinecraftPlayerActivity> statefulActivityStore() {
        Map<String, MinecraftPlayerActivity> store = new HashMap<>();
        when(repository.savePlayerActivity(any())).thenAnswer(invocation -> {
            MinecraftPlayerActivity activity = invocation.getArgument(0);
            store.put(activity.serverId() + ":" + activity.playerId(), activity);
            return activity;
        });
        when(repository.findPlayerActivity(anyString(), anyString())).thenAnswer(invocation ->
                Optional.ofNullable(store.get(invocation.getArgument(0) + ":" + invocation.getArgument(1))));
        return store;
    }

    private MinecraftPlayerEventCmd event(String playerId, String playerName, long eventAt, String subServer) {
        return new MinecraftPlayerEventCmd(playerId, playerName, eventAt, subServer);
    }

    private MinecraftPlayerSnapshotCmd snapshot(long observedAt, String playerId) {
        return new MinecraftPlayerSnapshotCmd(observedAt,
                List.of(new MinecraftPlayerSnapshotCmd.Player(playerId, "Steve")));
    }

    private MinecraftPlayerSnapshotCmd grouped(long observedAt, MinecraftPlayerSnapshotCmd.Server... servers) {
        return new MinecraftPlayerSnapshotCmd(observedAt, List.of(), Arrays.asList(servers));
    }

    private MinecraftPlayerSnapshotCmd.Server roster(String name, String... playerIds) {
        return new MinecraftPlayerSnapshotCmd.Server(name, Arrays.stream(playerIds)
                .map(playerId -> new MinecraftPlayerSnapshotCmd.Player(playerId, "Steve"))
                .toList());
    }

    private MinecraftServer serverWithMap(MinecraftServerMap map) {
        return new MinecraftServer(
                "server-1",
                "测试服",
                "",
                true,
                0,
                List.of(new MinecraftServerEndpoint(null, "主线", "play.example.com", 25565, null, true, true, 0)),
                List.of(new MinecraftServerSeason(null, "第一周目", null, BASE, null, true, 0, null)),
                map,
                BASE,
                BASE
        );
    }
}
