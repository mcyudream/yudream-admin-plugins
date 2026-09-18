package online.yudream.base.plugin.minecraft.infrastructure.repository;

import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivityEvent;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 事件流水的读取顺序。
 *
 * <p>代理换服时会在同一毫秒发出「旧子服 QUIT + 新子服 JOIN」。只按 occurredAt 排序时两者的先后
 * 由存储顺序决定，而顺序错了会让新子服的区间被旧 QUIT 立刻关掉——本机实测把一次 97 秒的会话算成
 * 0 分钟。因此同一时刻必须收尾先于开启。
 */
class MinecraftPlayerActivityEventOrderTest {

    @Test
    void closesAreSortedBeforeOpensAtTheSameInstant() {
        // 存储顺序故意把 JOIN 放在前面，模拟排序不稳定时的最坏情况。
        PluginDocumentStore documents = mock(PluginDocumentStore.class);
        when(documents.findByField(eq("player-activity-events"), eq("serverId"), eq("server-1"), anyInt(), anyInt()))
                .thenReturn(List.of(
                        event("join-paper", MinecraftPlayerActivityEvent.Type.JOIN, "paper", 5_000L),
                        event("quit-fabric", MinecraftPlayerActivityEvent.Type.QUIT, "fabric", 5_000L)));

        MinecraftServerDocumentRepository repository = new MinecraftServerDocumentRepository(documents);

        List<MinecraftPlayerActivityEvent> events =
                repository.listPlayerActivityEvents("server-1", "player-1", 1, 50);

        assertEquals(2, events.size());
        assertEquals(MinecraftPlayerActivityEvent.Type.QUIT, events.get(0).type());
        assertEquals("fabric", events.get(0).subServer());
        assertEquals(MinecraftPlayerActivityEvent.Type.JOIN, events.get(1).type());
        assertEquals("paper", events.get(1).subServer());
    }

    @Test
    void differentTimestampsStillOrderByTime() {
        PluginDocumentStore documents = mock(PluginDocumentStore.class);
        when(documents.findByField(eq("player-activity-events"), eq("serverId"), eq("server-1"), anyInt(), anyInt()))
                .thenReturn(List.of(
                        event("late-quit", MinecraftPlayerActivityEvent.Type.QUIT, "fabric", 9_000L),
                        event("early-join", MinecraftPlayerActivityEvent.Type.JOIN, "fabric", 1_000L)));

        MinecraftServerDocumentRepository repository = new MinecraftServerDocumentRepository(documents);

        List<MinecraftPlayerActivityEvent> events =
                repository.allPlayerActivityEvents("server-1");

        assertEquals(List.of("early-join", "late-quit"), events.stream()
                .map(MinecraftPlayerActivityEvent::id).toList());
    }

    private Map<String, Object> event(String id, MinecraftPlayerActivityEvent.Type type, String subServer, long at) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", id);
        document.put("serverId", "server-1");
        document.put("playerId", "player-1");
        document.put("playerName", "Steve");
        document.put("subServer", subServer);
        document.put("type", type.name());
        document.put("occurredAt", at);
        return document;
    }
}
