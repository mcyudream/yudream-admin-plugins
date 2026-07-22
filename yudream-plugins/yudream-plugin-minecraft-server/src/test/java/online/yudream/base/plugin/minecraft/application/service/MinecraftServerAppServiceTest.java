package online.yudream.base.plugin.minecraft.application.service;

import online.yudream.base.plugin.minecraft.application.cmd.MinecraftPlayerSnapshotCmd;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivity;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivityEvent;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServer;
import online.yudream.base.plugin.minecraft.domain.repo.MinecraftServerRepository;
import online.yudream.base.plugin.minecraft.infrastructure.service.MinecraftStatusService;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinecraftServerAppServiceTest {

    private static final long BASE = 1_700_000_000_000L;
    private MinecraftServerRepository repository;
    private MinecraftServerAppService service;

    @BeforeEach
    void setUp() {
        repository = mock(MinecraftServerRepository.class);
        PluginContext context = mock(PluginContext.class);
        when(context.framework()).thenReturn(mock(FrameworkServices.class));
        when(repository.findById("server-1")).thenReturn(Optional.of(mock(MinecraftServer.class)));
        service = new MinecraftServerAppService(repository, mock(MinecraftStatusService.class), context);
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

    private MinecraftPlayerSnapshotCmd snapshot(long observedAt, String playerId) {
        return new MinecraftPlayerSnapshotCmd(observedAt,
                List.of(new MinecraftPlayerSnapshotCmd.Player(playerId, "Steve")));
    }
}
