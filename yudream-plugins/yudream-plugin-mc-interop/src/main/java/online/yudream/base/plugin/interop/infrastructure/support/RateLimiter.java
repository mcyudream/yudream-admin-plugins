package online.yudream.base.plugin.interop.infrastructure.support;

import java.util.concurrent.atomic.AtomicLong;

/** 全局滑动窗口限流（对齐 mcpanel enroll 的 60s/30 次裁决）。 */
public class RateLimiter {

    private final int limit;
    private final long windowMillis;
    private final AtomicLong windowStart = new AtomicLong();
    private final AtomicLong windowCount = new AtomicLong();

    public RateLimiter(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    /** 超限返回 false（调用方映射 429）。 */
    public boolean tryAcquire(long now) {
        long start = windowStart.get();
        if (now - start > windowMillis) {
            if (windowStart.compareAndSet(start, now)) {
                windowCount.set(0);
            }
        }
        return windowCount.incrementAndGet() <= limit;
    }
}
