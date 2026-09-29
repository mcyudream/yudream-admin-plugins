package online.yudream.base.plugin.projectprogress.application.service;

import online.yudream.base.plugin.projectprogress.api.PluginProjectCheckInAcceptedListener;
import online.yudream.base.plugin.projectprogress.api.PluginProjectCheckInReward;
import online.yudream.base.plugin.projectprogress.api.PluginProjectProgressService;
import online.yudream.base.plugin.projectprogress.api.PluginProjectSummary;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectAcceptanceRecord;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectCheckInRecord;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectProgressProject;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectWorkDetail;
import online.yudream.base.plugin.projectprogress.domain.enumerate.ProjectCheckInReviewStatus;
import online.yudream.base.plugin.projectprogress.domain.enumerate.ProjectCheckInType;
import online.yudream.base.plugin.projectprogress.domain.repo.ProjectProgressRepository;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectAcceptedCheckIn;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectMinecraftEvidence;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 「打卡验收通过 → 奖励」契约的实现：既是 {@link PluginProjectProgressService} 的提供方，
 * 也负责在验收通过时向 {@link PluginProjectCheckInAcceptedListener} 逐条派发。
 *
 * <p>拉取与派发共用同一套映射与筛选（{@link #toReward} + 排除被驳回的打卡），因此两条口径天然一致：
 * 该细节下**未被驳回**的每条打卡记录各一次，幂等键都是打卡记录 id。</p>
 *
 * <p>派发是「通知」而不是「长任务」：只做一次批量读取与内存映射，监听者的耗时发放由监听者自己异步化。
 * 任何监听者抛出的 {@link RuntimeException} 与 {@link LinkageError} 都被逐个隔离并只记日志，
 * 验收结果与整段派发不受影响；没有监听者时连打卡记录都不读，避免无意义开销。</p>
 */
public class ProjectProgressRewardService implements PluginProjectProgressService {

    private static final Logger LOGGER = Logger.getLogger(ProjectProgressRewardService.class.getName());
    private static final int SCAN_PAGE_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ProjectProgressRepository repository;
    private final PluginContext pluginContext;

    public ProjectProgressRewardService(ProjectProgressRepository repository, PluginContext pluginContext) {
        this.repository = repository;
        this.pluginContext = pluginContext;
    }

    @Override
    public List<PluginProjectCheckInReward> acceptedCheckIns(long sinceAcceptedAt, int page, int size) {
        return repository.listAcceptedCheckIns(Math.max(sinceAcceptedAt, 0L), safePage(page), safeSize(size)).stream()
                .map(this::toReward)
                .toList();
    }

    @Override
    public List<PluginProjectSummary> projects() {
        List<PluginProjectSummary> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<ProjectProgressProject> batch = repository.listProjects(page, SCAN_PAGE_SIZE);
            batch.forEach(project -> result.add(new PluginProjectSummary(project.id(), project.name(), project.enabled())));
            if (batch.size() < SCAN_PAGE_SIZE) {
                return List.copyOf(result);
            }
            page++;
        }
    }

    /**
     * 工作细节验收通过落库之后调用：对该细节下的每条打卡记录，逐个监听者各回调一次。
     *
     * <p>整个方法都是「尽力而为」的：连读取失败也只记日志，调用方的验收事务与返回值不受影响。</p>
     *
     * @param project 验收后的项目
     * @param detail  验收通过后的工作细节（状态已是项目完成状态）
     * @param record  本次落库的验收通过记录，{@code createdAt} 即本次的 {@code acceptedAt}
     */
    public void dispatchAccepted(ProjectProgressProject project, ProjectWorkDetail detail, ProjectAcceptanceRecord record) {
        try {
            List<PluginProjectCheckInAcceptedListener> listeners = listeners();
            if (listeners.isEmpty()) {
                return;
            }
            for (ProjectCheckInRecord checkIn : allCheckIns(detail.id())) {
                PluginProjectCheckInReward reward = toReward(checkIn, detail, project, record.createdAt(),
                        record.operatorUserId());
                for (PluginProjectCheckInAcceptedListener listener : listeners) {
                    try {
                        listener.onCheckInAccepted(reward);
                    } catch (RuntimeException | LinkageError failure) {
                        LOGGER.log(Level.WARNING, "[project-progress] 验收通过积分回调失败：监听者 "
                                + listener.getClass().getName() + "，打卡记录 " + checkIn.id(), failure);
                    }
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(Level.WARNING, "[project-progress] 读取验收通过打卡记录失败，本次不做实时回调：细节 " + detail.id(), failure);
        }
    }

    /**
     * 已注册的验收通过监听者；provider 上下文缺失或扩展点读取失败时按「没有监听者」处理。
     *
     * <p>不缓存结果：扩展点可能随其它插件的 enable/disable 变化，缓存会漏掉后注册的消费方。</p>
     */
    private List<PluginProjectCheckInAcceptedListener> listeners() {
        if (pluginContext == null) {
            return List.of();
        }
        try {
            List<PluginProjectCheckInAcceptedListener> listeners =
                    pluginContext.extensions(PluginProjectCheckInAcceptedListener.class);
            return listeners == null ? List.of() : listeners;
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(Level.WARNING, "[project-progress] 读取验收通过监听者失败，本次不做实时回调", failure);
            return List.of();
        }
    }

    /**
     * 该细节下「算数」的打卡记录：**排除被驳回的**（驳回 = 证据不成立，不算这次打卡），
     * 按打卡记录 id 升序，与拉取口径（{@code listAcceptedCheckIns}）的排序与筛选完全一致。
     */
    private List<ProjectCheckInRecord> allCheckIns(String detailId) {
        List<ProjectCheckInRecord> result = new ArrayList<>();
        int page = 1;
        while (true) {
            List<ProjectCheckInRecord> batch = repository.listCheckIns(detailId, page, SCAN_PAGE_SIZE);
            for (ProjectCheckInRecord checkIn : batch) {
                if (checkIn.reviewStatus() == ProjectCheckInReviewStatus.REJECTED) {
                    continue;
                }
                result.add(checkIn);
            }
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
            page++;
        }
        result.sort(Comparator.comparing(ProjectCheckInRecord::id));
        return result;
    }

    private PluginProjectCheckInReward toReward(ProjectAcceptedCheckIn row) {
        return toReward(row.checkIn(), row.detail(), row.project(), row.acceptedAt(), row.acceptedByUserId());
    }

    private PluginProjectCheckInReward toReward(ProjectCheckInRecord checkIn, ProjectWorkDetail detail,
                                                ProjectProgressProject project, long acceptedAt, String acceptedBy) {
        return new PluginProjectCheckInReward(checkIn.id(), detail.id(), detail.title(), project.id(), project.name(),
                checkIn.userId(), checkIn.type().name(), checkIn.createdAt(), acceptedAt, acceptedBy,
                effectiveMillis(checkIn));
    }

    /**
     * 本条打卡的窗口内有效在线毫秒数，供按时薪折算奖励的消费方使用。
     *
     * <p>取值口径与打卡记录页、导出展示的「有效在线 XX 分钟」完全一致，就是证据里的
     * {@code ProjectMinecraftEvidence.effectiveOnlineMillis}（项目未开启「计入挂机」时即「在线 − 挂机」，
     * 开启时按在线计）。判定是否达标的窗口值与展示用的是同一个字段，因此不需要二次换算。</p>
     *
     * <p>非 Minecraft 在线时长打卡、证据缺失（不含该证据的打卡类型）或按本字段之前的口径落库的历史记录
     * （证据文档里没有 {@code effectiveOnlineMillis}，读回来是 {@code null}）一律返回 {@code 0}：
     * 消费方按「本条没有可折算时长」处理，不会因为缺少时长而报错。</p>
     */
    private static long effectiveMillis(ProjectCheckInRecord checkIn) {
        if (checkIn.type() != ProjectCheckInType.MINECRAFT_ONLINE) {
            return 0L;
        }
        ProjectMinecraftEvidence evidence = checkIn.minecraft();
        return evidence == null ? 0L : evidence.effectiveOnlineMillis();
    }

    private int safePage(int page) {
        return Math.max(page, 1);
    }

    private int safeSize(int size) {
        return Math.max(Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE), 1);
    }
}
