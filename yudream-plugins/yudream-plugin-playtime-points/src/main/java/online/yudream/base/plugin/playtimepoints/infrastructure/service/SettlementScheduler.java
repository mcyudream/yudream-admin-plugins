package online.yudream.base.plugin.playtimepoints.infrastructure.service;

import online.yudream.base.plugin.playtimepoints.application.service.CheckInRewardService;
import online.yudream.base.plugin.playtimepoints.application.service.PlaytimePointsAppService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务：每 60 秒扫描一轮 mc-server 玩家活动，对已退出的会话做增量结算；
 * 另每 5 分钟做一轮项目打卡积分的增量拉取（project-progress 软依赖）。
 *
 * <p>打卡拉取单独用更长的间隔：它要遍历 provider 的验收记录，属于对账性质的补偿路径，
 * 实时回调（可选开关）才是低延迟通道。总开关关闭时 {@code scan()} 立即返回，不产生任何读取。</p>
 *
 * <p>启用阶段只调度不执行：两个任务都带初始延迟，{@code onEnable} 本身不做拉取。</p>
 */
public class SettlementScheduler implements AutoCloseable {

    private static final long INITIAL_DELAY_SECONDS = 45;
    private static final long SCAN_INTERVAL_SECONDS = 60;
    private static final long CHECK_IN_INITIAL_DELAY_SECONDS = 90;
    private static final long CHECK_IN_SCAN_INTERVAL_SECONDS = 300;

    private final PlaytimePointsAppService appService;
    private final CheckInRewardService checkInRewards;
    private ScheduledExecutorService executor;

    public SettlementScheduler(PlaytimePointsAppService appService, CheckInRewardService checkInRewards) {
        this.appService = appService;
        this.checkInRewards = checkInRewards;
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
        if (checkInRewards != null) {
            executor.scheduleWithFixedDelay(this::scanCheckInRewardsSafely, CHECK_IN_INITIAL_DELAY_SECONDS,
                    CHECK_IN_SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
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

    private void scanCheckInRewardsSafely() {
        try {
            checkInRewards.scan();
        } catch (RuntimeException | LinkageError ignored) {
            // 打卡拉取失败只影响本轮；游标未越过的记录下一轮重试。
            // LinkageError 也要挡住：消费方按 project-progress 1.6.0 编译，宿主上仍运行更早版本时，
            // 读取新字段会抛 NoSuchMethodError（Error 而非 RuntimeException），而周期任务一旦抛出
            // Error 就会被调度器取消，之后再也不会有补发轮次。
        }
    }
}
