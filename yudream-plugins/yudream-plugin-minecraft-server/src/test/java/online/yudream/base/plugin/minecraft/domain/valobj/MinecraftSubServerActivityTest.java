package online.yudream.base.plugin.minecraft.domain.valobj;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftSubServerActivityTest {

    @Test
    void accumulatesOnlineAndAfkSeparatelyPerSubServer() {
        MinecraftSubServerActivity bucket = MinecraftSubServerActivity.empty("fabric")
                .join(1_000)
                .startAfk(2_000)
                .quit(4_000);

        assertFalse(bucket.online());
        assertFalse(bucket.afk());
        assertEquals(3_000, bucket.onlineMillis());
        assertEquals(2_000, bucket.afkMillis());
        assertEquals(1_000, bucket.lastJoinedAt());
        assertEquals(4_000, bucket.lastQuitAt());
        assertNull(bucket.currentOnlineSince());
        assertNull(bucket.currentAfkSince());
    }

    @Test
    void repeatedQuitCannotAddTimeTwice() {
        MinecraftSubServerActivity bucket = MinecraftSubServerActivity.empty("fabric")
                .join(1_000)
                .quit(4_000)
                .quit(9_000);

        assertEquals(3_000, bucket.onlineMillis());
        assertEquals(0, bucket.afkMillis());
    }

    @Test
    void repeatedJoinDoesNotResetTheStartOfTheInterval() {
        MinecraftSubServerActivity bucket = MinecraftSubServerActivity.empty("fabric")
                .join(1_000)
                .join(3_000)
                .quit(5_000);

        assertEquals(4_000, bucket.onlineMillis());
    }

    @Test
    void startAfkImpliesOnline() {
        MinecraftSubServerActivity bucket = MinecraftSubServerActivity.empty("paper").startAfk(1_000);

        assertTrue(bucket.online());
        assertTrue(bucket.afk());
        assertEquals(1_000, bucket.currentOnlineSince());
        assertEquals(1_000, bucket.currentAfkSince());
    }

    @Test
    void openIntervalsAreIncludedByOnlineAtAndAfkAt() {
        MinecraftSubServerActivity bucket = MinecraftSubServerActivity.empty("fabric")
                .join(1_000)
                .startAfk(2_000);

        assertEquals(9_000, bucket.onlineAt(10_000));
        assertEquals(8_000, bucket.afkAt(10_000));
        assertEquals(0, bucket.onlineMillis());
        assertEquals(0, bucket.afkMillis());
    }

    @Test
    void blankNamesFallIntoTheDefaultBucket() {
        assertEquals(MinecraftSubServerActivity.DEFAULT_NAME, MinecraftSubServerActivity.normalizeName(null));
        assertEquals(MinecraftSubServerActivity.DEFAULT_NAME, MinecraftSubServerActivity.normalizeName("   "));
        assertEquals("fabric", MinecraftSubServerActivity.normalizeName(" fabric "));
        assertTrue(MinecraftSubServerActivity.empty("  ").isDefaultBucket());
        assertFalse(MinecraftSubServerActivity.empty("fabric").isDefaultBucket());
    }

    @Test
    void aBlankNameStillValidates() {
        assertTrue(MinecraftSubServerActivity.empty(null).isDefaultBucket());
    }
}
