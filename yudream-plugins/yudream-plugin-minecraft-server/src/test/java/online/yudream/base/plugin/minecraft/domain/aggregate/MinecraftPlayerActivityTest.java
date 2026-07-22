package online.yudream.base.plugin.minecraft.domain.aggregate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
