package online.yudream.base.plugin.eduverify.infrastructure;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/** 公开上传滑动窗口限流。进程内计数，插件重载后清零。 */
public final class UploadRateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public void acquire(String key, int maxInWindow, long windowMs, long minIntervalMs, int dailyLimit, String dayBucket) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("缺少限流键");
        }
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, ignored -> new Window());
        synchronized (window) {
            prune(window, now, windowMs);
            if (minIntervalMs > 0 && window.lastAt > 0 && now - window.lastAt < minIntervalMs) {
                throw new IllegalArgumentException("上传过于频繁，请稍后再试");
            }
            if (window.stamps.size() >= maxInWindow) {
                throw new IllegalArgumentException("上传过于频繁，请稍后再试");
            }
            if (!dayBucket.equals(window.dayBucket)) {
                window.dayBucket = dayBucket;
                window.dayCount = 0;
            }
            if (window.dayCount >= dailyLimit) {
                throw new IllegalArgumentException("今日上传次数已达上限");
            }
            window.stamps.addLast(now);
            window.lastAt = now;
            window.dayCount++;
        }
    }

    private static void prune(Window window, long now, long windowMs) {
        Iterator<Long> iterator = window.stamps.iterator();
        while (iterator.hasNext()) {
            Long stamp = iterator.next();
            if (stamp == null || now - stamp > windowMs) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    private static final class Window {
        private final ArrayDeque<Long> stamps = new ArrayDeque<>();
        private long lastAt;
        private String dayBucket = "";
        private int dayCount;
    }
}
