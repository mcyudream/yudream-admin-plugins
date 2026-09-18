package online.yudream.base.plugin.minecraft.domain.aggregate;

import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServerActivity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftPlayerActivityTest {

    @Test
    void serverOfflineClosesOnlineAndAfkIntervalsAtLastTrustedTime() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 1_000)
                .join("Steve", 1_000)
                .startAfk("Steve", 2_000);

        MinecraftPlayerActivity recovered = activity.quit("Steve", 4_000);

        assertFalse(recovered.online());
        assertFalse(recovered.afk());
        assertEquals(3_000, recovered.totalOnlineMillis());
        assertEquals(2_000, recovered.totalAfkMillis());
        assertEquals(4_000, recovered.lastQuitAt());
        assertNull(recovered.currentOnlineSince());
        assertNull(recovered.currentAfkSince());
    }

    @Test
    void repeatedOfflineRecoveryCannotAddTimeTwice() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 1_000)
                .join("Steve", 1_000)
                .quit("Steve", 4_000);

        MinecraftPlayerActivity recoveredAgain = activity.quit("Steve", 9_000);

        assertEquals(3_000, recoveredAgain.totalOnlineMillis());
        assertEquals(0, recoveredAgain.totalAfkMillis());
    }

    // ------------------------------------------------------------------ 子服拆分

    @Test
    void timeSpentOnDifferentSubServersAccumulatesIndependently() {
        // 玩家换服：fabric 上 60 秒，paper 上 30 秒，合计 90 秒。
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 0)
                .join("fabric", "Steve", 1_000)
                .quit("fabric", "Steve", 61_000)
                .join("paper", "Steve", 61_000)
                .quit("paper", "Steve", 91_000);

        assertEquals(60_000, activity.subServers().get("fabric").onlineMillis());
        assertEquals(30_000, activity.subServers().get("paper").onlineMillis());
        assertEquals(90_000, activity.totalOnlineMillis());
        assertEquals(2, activity.subServers().size());
        assertFalse(activity.online());
    }

    @Test
    void leavingOneSubServerKeepsThePlayerOnlineOnAnother() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 0)
                .join("fabric", "Steve", 1_000)
                .join("paper", "Steve", 2_000)
                .quit("fabric", "Steve", 5_000);

        assertTrue(activity.online());
        assertEquals("paper", activity.subServers().values().stream()
                .filter(MinecraftSubServerActivity::online).findFirst().orElseThrow().name());
        assertEquals(4_000, activity.totalOnlineMillis());
        // 汇总的“在线起点”是仍在线的那个子服的起点。
        assertEquals(2_000L, activity.currentOnlineSince());
        // 已经离开的子服仍然记下了它的退出时间。
        assertEquals(5_000L, activity.subServers().get("fabric").lastQuitAt());
    }

    @Test
    void aggregateFieldsAlwaysMirrorTheSubServerMap() {
        Map<String, MinecraftSubServerActivity> buckets = new LinkedHashMap<>();
        buckets.put("fabric", new MinecraftSubServerActivity("fabric", 10_000, 1_000, null, null, 1L, 2L));
        buckets.put("paper", new MinecraftSubServerActivity("paper", 20_000, 2_000, null, null, 3L, 4L));

        // 故意传入与 map 不一致的汇总值：构造器必须以 map 为准，不允许漂移。
        MinecraftPlayerActivity activity = new MinecraftPlayerActivity("id", "server-1", "player-1", "Steve",
                999, 999, 5_000L, 5_000L, 111L, 222L, 1L, 1L, buckets);

        assertEquals(30_000, activity.totalOnlineMillis());
        assertEquals(3_000, activity.totalAfkMillis());
        assertNull(activity.currentOnlineSince());
        assertNull(activity.currentAfkSince());
        assertEquals(3L, activity.lastJoinedAt());
        assertEquals(4L, activity.lastQuitAt());
    }

    @Test
    void oldSignatureOperatesOnTheDefaultBucket() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 0)
                .join("Steve", 1_000)
                .quit("Steve", 4_000);

        assertEquals(1, activity.subServers().size());
        assertTrue(activity.subServers().containsKey(MinecraftPlayerActivity.DEFAULT_SUB_SERVER));
        assertEquals(3_000, activity.totalOnlineMillis());
    }

    @Test
    void blankSubServerNameFallsIntoTheDefaultBucket() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 0)
                .join("   ", "Steve", 1_000)
                .quit(null, "Steve", 4_000);

        assertEquals(1, activity.subServers().size());
        assertEquals(3_000, activity.totalOnlineMillis());
    }

    @Test
    void legacyDocumentWithoutSubServersIsSeededFromTheFlatTotals() {
        // 这是本次改动前写入 Mongo 的形态：只有汇总字段，没有 subServers。
        MinecraftPlayerActivity legacy = new MinecraftPlayerActivity("server-1:player-1", "server-1", "player-1",
                "Steve", 42_000, 7_000, 90_000L, 91_000L, 80_000L, 92_000L, 1L, 92_000L, Map.of());

        assertEquals(1, legacy.subServers().size());
        MinecraftSubServerActivity bucket = legacy.subServers().get(MinecraftPlayerActivity.DEFAULT_SUB_SERVER);
        assertEquals(42_000, bucket.onlineMillis());
        assertEquals(7_000, bucket.afkMillis());
        assertEquals(90_000L, bucket.currentOnlineSince());
        assertEquals(91_000L, bucket.currentAfkSince());
        assertTrue(legacy.online());
        assertEquals(42_000, legacy.totalOnlineMillis());
        assertEquals(90_000L, legacy.currentOnlineSince());
    }

    @Test
    void quitAllClosesEverySubServerStillOnline() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 0)
                .join("fabric", "Steve", 1_000)
                .join("paper", "Steve", 2_000)
                .quitAll("Steve", 5_000);

        assertFalse(activity.online());
        assertEquals(4_000 + 3_000, activity.totalOnlineMillis());
        assertEquals(5_000L, activity.lastQuitAt());
    }

    @Test
    void quitAllOnANeverOnlineActivityChangesNothing() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 1_000)
                .quitAll("Steve", 9_000);

        assertEquals(0, activity.totalOnlineMillis());
        assertTrue(activity.subServers().isEmpty());
    }

    @Test
    void closeSubServerOnlyTouchesThatSubServer() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 0)
                .join("fabric", "Steve", 1_000)
                .join("paper", "Steve", 2_000)
                .closeSubServer("fabric", 5_000);

        assertTrue(activity.online());
        assertFalse(activity.subServers().get("fabric").online());
        assertTrue(activity.subServers().get("paper").online());
        // 不在线的子服再关一次是空操作。
        assertEquals(activity, activity.closeSubServer("fabric", 9_000));
    }

    @Test
    void openIntervalsOnEverySubServerAreIncludedInTheAggregate() {
        MinecraftPlayerActivity activity = MinecraftPlayerActivity.empty("server-1", "player-1", "Steve", 0)
                .join("fabric", "Steve", 1_000)
                .join("paper", "Steve", 4_000);

        assertEquals(9_000 + 6_000, activity.totalOnlineMillisAt(10_000));
        assertEquals(centralOnlineSince(activity), activity.currentOnlineSince());
    }

    private long centralOnlineSince(MinecraftPlayerActivity activity) {
        return activity.subServers().values().stream()
                .map(MinecraftSubServerActivity::currentOnlineSince)
                .min(Long::compareTo)
                .orElseThrow();
    }
}
