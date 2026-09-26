package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 认证限速：窗口内第 N 次失败封禁该来源，成功重置，窗口滑动自动解封。 */
class AuthRateLimiterTest {

    @Test
    void blocksAfterLimitAndReleasesOnWindowRoll() {
        AuthRateLimiter limiter = new AuthRateLimiter(3, 1_000L);
        long now = 0L;

        limiter.recordFailure("1.2.3.4", now);
        limiter.recordFailure("1.2.3.4", now);
        assertFalse(limiter.blocked("1.2.3.4", now));

        limiter.recordFailure("1.2.3.4", now);
        assertTrue(limiter.blocked("1.2.3.4", now));

        // 窗口滑走后自动解封。
        assertFalse(limiter.blocked("1.2.3.4", now + 1_001L));
    }

    @Test
    void sourcesAreIndependentAndSuccessResetsCounter() {
        AuthRateLimiter limiter = new AuthRateLimiter(2, 10_000L);

        limiter.recordFailure("1.1.1.1", 0L);
        limiter.recordFailure("1.1.1.1", 0L);
        assertTrue(limiter.blocked("1.1.1.1", 0L));
        assertFalse(limiter.blocked("2.2.2.2", 0L));

        limiter.recordFailure("2.2.2.2", 0L);
        limiter.reset("2.2.2.2");
        assertFalse(limiter.blocked("2.2.2.2", 0L));
    }
}
