package online.yudream.base.plugin.minecraft.application.service;

import online.yudream.base.plugin.minecraft.application.cmd.MinecraftPlayerSnapshotCmd;
import online.yudream.base.plugin.minecraft.application.dto.MinecraftServerDTO;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivity;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivityEvent;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServer;
import online.yudream.base.plugin.minecraft.domain.repo.MinecraftServerRepository;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerEndpoint;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerMap;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerSeason;
import online.yudream.base.plugin.minecraft.infrastructure.service.MinecraftStatusService;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private MinecraftPlayerSnapshotCmd snapshot(long observedAt, String playerId) {
        return new MinecraftPlayerSnapshotCmd(observedAt,
                List.of(new MinecraftPlayerSnapshotCmd.Player(playerId, "Steve")));
    }

    private MinecraftServer serverWithMap(MinecraftServerMap map) {
        return new MinecraftServer(
                "server-1",
                "测试服",
                "",
                true,
                0,
                List.of(new MinecraftServerEndpoint(null, "主线", "play.example.com", 25565, null, true, true, 0)),
                List.of(new MinecraftServerSeason(null, "第一周目", null, BASE, null, true, 0)),
                map,
                BASE,
                BASE
        );
    }
}
