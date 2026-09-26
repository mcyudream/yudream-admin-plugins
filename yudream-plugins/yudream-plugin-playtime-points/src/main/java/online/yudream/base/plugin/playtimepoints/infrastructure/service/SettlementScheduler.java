package online.yudream.base.plugin.playtimepoints.infrastructure.service;

import online.yudream.base.plugin.playtimepoints.application.service.PlaytimePointsAppService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 结算调度器：每分钟扫一轮 mc-server 玩家活动，对已退出的会话做增量结算。 */
public class SettlementScheduler implements AutoCloseable {

    private static final long INITIAL_DELAY_SECONDS = 45;
    private static final long SCAN_INTERVAL_SECONDS = 60;

    private final PlaytimePointsAppService appService;
    private ScheduledExecutorService executor;

    public SettlementScheduler(PlaytimePointsAppService appService) {
        this.appService = appService;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "playtime-points-settlement");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::scanSafely, INITIAL_DELAY_SECONDS, SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public synchronized void close() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor = null;
    }

    private void scanSafely() {
        try {
            appService.scan();
        } catch (RuntimeException ignored) {
            // 单轮失败只影响本轮结算，下一轮重试；状态未推进的会话会自动补发
        }
    }
}
