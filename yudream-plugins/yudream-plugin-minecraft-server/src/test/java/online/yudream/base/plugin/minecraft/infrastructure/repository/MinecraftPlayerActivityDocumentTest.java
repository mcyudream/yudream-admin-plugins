package online.yudream.base.plugin.minecraft.infrastructure.repository;

import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftPlayerActivity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家活动文档的读取兼容性。
 *
 * <p>本次改动新增了子服拆分，但已经写进 Mongo 的文档没有 {@code subServers} 键。读取必须容忍这种
 * 旧形态，并用原来的汇总字段补出默认桶，否则升级后历史时长会凭空消失。
 */
class MinecraftPlayerActivityDocumentTest {

    @Test
    void readsALegacyDocumentWithoutSubServers() throws Exception {
        Map<String, Object> document = legacyDocument();

        MinecraftPlayerActivity activity = invokeToPlayerActivity(document);

        assertEquals(1, activity.subServers().size());
        assertEquals(MinecraftPlayerActivity.DEFAULT_SUB_SERVER,
                activity.subServers().keySet().iterator().next());
        assertEquals(42_000, activity.totalOnlineMillis());
        assertEquals(7_000, activity.totalAfkMillis());
        assertTrue(activity.online());
    }

    @Test
    void readsTheSubServerBreakdownBack() throws Exception {
        Map<String, Object> document = legacyDocument();
        document.put("totalOnlineMillis", 90_000L);
        Map<String, Object> fabric = new LinkedHashMap<>();
        fabric.put("name", "fabric");
        fabric.put("onlineMillis", 60_000L);
        fabric.put("afkMillis", 0L);
        Map<String, Object> paper = new LinkedHashMap<>();
        paper.put("name", "paper");
        paper.put("onlineMillis", 30_000L);
        paper.put("afkMillis", 1_000L);
        document.put("subServers", List.of(fabric, paper));

        MinecraftPlayerActivity activity = invokeToPlayerActivity(document);

        assertEquals(2, activity.subServers().size());
        assertEquals(60_000, activity.subServers().get("fabric").onlineMillis());
        assertEquals(30_000, activity.subServers().get("paper").onlineMillis());
        // 顶层汇总始终由拆分推导，文档里的旧汇总值不再被采信。
        assertEquals(90_000, activity.totalOnlineMillis());
        assertEquals(1_000, activity.totalAfkMillis());
    }

    @Test
    void aBlankSubServerNameReadsBackAsTheDefaultBucket() throws Exception {
        Map<String, Object> document = legacyDocument();
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("name", "  ");
        blank.put("onlineMillis", 5_000L);
        document.put("subServers", List.of(blank));

        MinecraftPlayerActivity activity = invokeToPlayerActivity(document);

        assertTrue(activity.subServers().containsKey(MinecraftPlayerActivity.DEFAULT_SUB_SERVER));
        assertEquals(5_000, activity.totalOnlineMillis());
    }

    private Map<String, Object> legacyDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", "server-1:player-1");
        document.put("serverId", "server-1");
        document.put("playerId", "player-1");
        document.put("playerName", "Steve");
        document.put("totalOnlineMillis", 42_000L);
        document.put("totalAfkMillis", 7_000L);
        document.put("currentOnlineSince", 90_000L);
        document.put("currentAfkSince", 91_000L);
        document.put("lastJoinedAt", 80_000L);
        document.put("lastQuitAt", 92_000L);
        document.put("createdAt", 1L);
        document.put("updatedAt", 92_000L);
        return document;
    }

    @SuppressWarnings("unchecked")
    private MinecraftPlayerActivity invokeToPlayerActivity(Map<String, Object> document) throws Exception {
        Method method = MinecraftServerDocumentRepository.class.getDeclaredMethod("toPlayerActivity", Map.class);
        method.setAccessible(true);
        return (MinecraftPlayerActivity) method.invoke(new MinecraftServerDocumentRepository(null), document);
    }
}
