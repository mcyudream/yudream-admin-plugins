package online.yudream.base.plugin.minecraft.infrastructure.repository;

import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivityEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 活动事件文档的读写。
 *
 * <p>事件流是时间窗统计（在线/挂机/有效时长）的唯一依据。改造前它不记录子服，因此「按子服统计」
 * 完全依赖这里把 {@code subServer} 正确写进去、并正确读回来——键名写错或漏读都会静默丢掉子服维度，
 * 而服务层测试不会发现。
 */
class MinecraftPlayerActivityEventDocumentTest {

    @Test
    void writesAndReadsTheSubServerBack() throws Exception {
        MinecraftPlayerActivityEvent event = MinecraftPlayerActivityEvent.create(
                "server-1", "player-1", "Steve", "fabric", MinecraftPlayerActivityEvent.Type.JOIN, 1_000L);

        Map<String, Object> document = invokeToDocument(event);

        assertEquals("fabric", document.get("subServer"));

        MinecraftPlayerActivityEvent read = invokeToEvent(document);
        assertEquals("fabric", read.subServer());
        assertEquals(MinecraftPlayerActivityEvent.Type.JOIN, read.type());
        assertEquals(1_000L, read.occurredAt());
    }

    /** 整服事件不写这个键，文档与改造前逐字节一致。 */
    @Test
    void wholeServerEventsDoNotWriteTheKey() throws Exception {
        MinecraftPlayerActivityEvent event = MinecraftPlayerActivityEvent.create(
                "server-1", "player-1", "Steve", MinecraftPlayerActivityEvent.Type.SERVER_OFFLINE, 1_000L);

        Map<String, Object> document = invokeToDocument(event);

        assertFalse(document.containsKey("subServer"));
        assertEquals("", invokeToEvent(document).subServer());
    }

    /**
     * 改造之前写入的事件没有 {@code subServer} 键。
     *
     * <p>读取必须容忍，并归入「无子服维度」。这类事件在按子服回放时不能被当成「属于每台子服」——
     * 它没有子服信息，归属只能靠与之配对的收尾事件补，补不上就不计入任何具名子服。
     */
    @Test
    void legacyEventsWithoutTheKeyReadAsNoSubServerDimension() throws Exception {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", "event-1");
        document.put("serverId", "server-1");
        document.put("playerId", "player-1");
        document.put("playerName", "Steve");
        document.put("type", MinecraftPlayerActivityEvent.Type.QUIT.name());
        document.put("occurredAt", 2_000L);

        MinecraftPlayerActivityEvent read = invokeToEvent(document);

        assertEquals("", read.subServer());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeToDocument(MinecraftPlayerActivityEvent event) throws Exception {
        Method method = MinecraftServerDocumentRepository.class
                .getDeclaredMethod("playerActivityEventDocument", MinecraftPlayerActivityEvent.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(new MinecraftServerDocumentRepository(null), event);
    }

    private MinecraftPlayerActivityEvent invokeToEvent(Map<String, Object> document) throws Exception {
        Method method = MinecraftServerDocumentRepository.class
                .getDeclaredMethod("toPlayerActivityEvent", Map.class);
        method.setAccessible(true);
        return (MinecraftPlayerActivityEvent) method.invoke(new MinecraftServerDocumentRepository(null), document);
    }
}
