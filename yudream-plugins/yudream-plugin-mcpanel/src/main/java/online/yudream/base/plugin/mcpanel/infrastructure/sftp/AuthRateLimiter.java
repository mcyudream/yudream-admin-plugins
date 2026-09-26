package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关认证限速：按来源 IP 的固定窗口失败计数。别名只是路由键、可被枚举，
 * 真正的防线是随机密码 + 对爆破行为的窗口封禁。
 */
public final class AuthRateLimiter {

    private final int maxFailures;
    private final long windowMs;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public AuthRateLimiter(int maxFailures, long windowMs) {
        this.maxFailures = Math.max(1, maxFailures);
        this.windowMs = windowMs;
    }

    /** 窗口内失败达到上限即封禁（窗口滑动到下一周期自动解除）。 */
    public boolean blocked(String remote, long nowMs) {
        Window window = windows.get(remote);
        return window != null && window.count(nowMs) >= maxFailures;
    }

    public void recordFailure(String remote, long nowMs) {
        windows.compute(remote, (key, window) -> {
            Window current = window == null ? new Window() : window;
            current.increment(nowMs);
            return current;
        });
    }

    /** 认证成功后清零该来源的失败计数。 */
    public void reset(String remote) {
        windows.remove(remote);
    }

    private final class Window {
        private long windowStartMs;
        private int failures;

        private synchronized int count(long nowMs) {
            rollIfNeeded(nowMs);
            return failures;
        }

        private synchronized void increment(long nowMs) {
            rollIfNeeded(nowMs);
            failures++;
        }

        private void rollIfNeeded(long nowMs) {
            if (nowMs - windowStartMs >= windowMs) {
                windowStartMs = nowMs;
                failures = 0;
            }
        }
    }
}
