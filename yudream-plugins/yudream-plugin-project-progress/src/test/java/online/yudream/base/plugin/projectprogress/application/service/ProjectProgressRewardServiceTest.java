package online.yudream.base.plugin.projectprogress.application.service;

import online.yudream.base.plugin.projectprogress.api.PluginProjectCheckInAcceptedListener;
import online.yudream.base.plugin.projectprogress.api.PluginProjectCheckInReward;
import online.yudream.base.plugin.projectprogress.api.PluginProjectProgressService;
import online.yudream.base.plugin.projectprogress.api.PluginProjectSummary;
import online.yudream.base.plugin.projectprogress.application.cmd.ProjectProgressAcceptanceCmd;
import online.yudream.base.plugin.projectprogress.application.dto.ProjectAcceptanceDTO;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectAcceptanceRecord;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectCheckInRecord;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectProgressProject;
import online.yudream.base.plugin.projectprogress.domain.aggregate.ProjectWorkDetail;
import online.yudream.base.plugin.projectprogress.domain.enumerate.ProjectAcceptanceResult;
import online.yudream.base.plugin.projectprogress.domain.enumerate.ProjectAssignmentMode;
import online.yudream.base.plugin.projectprogress.domain.enumerate.ProjectCheckInReviewStatus;
import online.yudream.base.plugin.projectprogress.domain.enumerate.ProjectCheckInType;
import online.yudream.base.plugin.projectprogress.domain.repo.ProjectProgressRepository;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectFileEvidence;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectMinecraftEvidence;
import online.yudream.base.plugin.projectprogress.domain.valobj.ProjectMinecraftPolicy;
import online.yudream.base.plugin.projectprogress.infrastructure.repository.ProjectProgressDocumentRepository;
import online.yudream.base.plugin.projectprogress.support.FakeDocumentStore;
import online.yudream.base.plugin.projectprogress.support.FakePluginContext;
import online.yudream.base.plugin.spi.core.PluginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打卡验收通过 → 奖励契约的用例。
 *
 * <p>真实文档仓储（内存文档存储）+ 真实验收用例（{@link ProjectProgressAppService#accept}）：
 * 覆盖「每条打卡各一次」「分页与含下界游标」「同一打卡多轮验收只算最后一次」「实时回调逐条派发」
 * 「监听者异常被隔离」「没有监听者时不做额外读取」。</p>
 */
class ProjectProgressRewardServiceTest {

    private static final String ACCEPTOR = "9001";
    private static final String USER = "8001";
    private static final String OTHER_USER = "8002";

    private ProjectProgressDocumentRepository documentRepository;
    private ProjectProgressRepository repository;
    private AtomicInteger checkInReads;
    private List<PluginProjectCheckInAcceptedListener> listeners;
    private PluginProjectProgressService rewards;
    private ProjectProgressAppService appService;

    @BeforeEach
    void setUp() {
        documentRepository = new ProjectProgressDocumentRepository(new FakeDocumentStore());
        checkInReads = new AtomicInteger();
        repository = countingRepository(documentRepository, checkInReads);
        listeners = new ArrayList<>();
        appService = new ProjectProgressAppService(repository, FakePluginContext.fileStore(), null,
                FakePluginContext.withExtensions(listeners));
        rewards = appService.rewards();
    }

    // ------------------------------------------------------------------ 拉取口径

    /** 验收通过的细节下，每条打卡都能拉到；未验收细节与挂项目的打卡拉不到。 */
    @Test
    void acceptedCheckInsReturnsEveryCheckInOfAcceptedDetailOnly() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail accepted = reviewingDetail(project, "铺设石砖路");
        ProjectWorkDetail pending = reviewingDetail(project, "搭建牌楼");
        checkIn("c001", project, accepted.id(), USER, 500);
        checkIn("c002", project, accepted.id(), OTHER_USER, 600);
        checkIn("c003", project, accepted.id(), USER, 700);
        checkIn("c004", project, pending.id(), USER, 800);
        // 挂在项目上、不属于任何细节的打卡：永远不该被算作「细节验收通过」
        checkIn("c005", project, "", USER, 900);
        acceptance("a001", project, accepted.id(), 2_000L);

        List<PluginProjectCheckInReward> rows = rewards.acceptedCheckIns(0, 1, 50);

        assertEquals(List.of("c001", "c002", "c003"), ids(rows),
                "只返回已验收细节下的每条打卡，未验收细节与项目级打卡都不返回");
        PluginProjectCheckInReward first = rows.getFirst();
        assertEquals(accepted.id(), first.detailId());
        assertEquals("铺设石砖路", first.detailTitle());
        assertEquals(project.id(), first.projectId());
        assertEquals("古城改造", first.projectName());
        assertEquals(USER, first.userId());
        assertEquals("IMAGE", first.checkInType());
        assertEquals(500, first.checkInAt());
        assertEquals(2_000L, first.acceptedAt());
        assertEquals(ACCEPTOR, first.acceptedBy());
    }

    /**
     * effectiveMillis：Minecraft 在线时长打卡带出证据里窗口内的有效在线毫秒数；
     * 非 Minecraft 打卡（没有 MC 证据）为 0。
     */
    @Test
    void acceptedCheckInsCarriesEffectiveOnlineMillisForMinecraftCheckIns() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail detail = reviewingDetail(project, "铺设石砖路");
        long effective = 44 * 60_000L;
        minecraftCheckIn("c001", project, detail.id(), USER, 10, effective);
        checkIn("c002", project, detail.id(), USER, 11);
        acceptance("a001", project, detail.id(), 2_000L);

        List<PluginProjectCheckInReward> rows = rewards.acceptedCheckIns(0, 1, 50);

        assertEquals(List.of("c001", "c002"), ids(rows));
        assertEquals(effective, rows.getFirst().effectiveMillis(),
                "MC 自动打卡带出证据里窗口内的有效在线毫秒数（与打卡记录页显示的「有效在线 XX 分钟」同一口径）");
        assertEquals("MINECRAFT_ONLINE", rows.getFirst().checkInType());
        assertEquals(0L, rows.get(1).effectiveMillis(), "非 MC 打卡没有可折算时长，为 0");
        assertEquals("IMAGE", rows.get(1).checkInType());
    }

    /** 旧数据：MC 打卡但证据被改写/缺失（没有 effectiveOnlineMillis）时按 0 处理，不抛错。 */
    @Test
    void minecraftCheckInWithoutEvidenceReportsZeroEffectiveMillis() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail detail = reviewingDetail(project, "铺设石砖路");
        legacyMinecraftCheckIn("c001", project, detail.id(), USER, 10);
        acceptance("a001", project, detail.id(), 2_000L);

        List<PluginProjectCheckInReward> rows = rewards.acceptedCheckIns(0, 1, 50);

        assertEquals(1, rows.size());
        assertEquals("MINECRAFT_ONLINE", rows.getFirst().checkInType());
        assertEquals(0L, rows.getFirst().effectiveMillis(), "证据缺失的历史记录读成 0，消费方按「本条没有可折算时长」处理");
    }

    /** 实时回调与拉取带出的有效毫秒完全一致（两条路径共用同一套映射）。 */
    @Test
    void realtimeCallbackCarriesSameEffectiveMillisAsPull() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail detail = reviewingDetail(project, "铺设石砖路");
        long effective = 90 * 60_000L;
        minecraftCheckIn("c001", project, detail.id(), USER, 10, effective);
        checkIn("c002", project, detail.id(), OTHER_USER, 11);
        List<PluginProjectCheckInReward> received = new ArrayList<>();
        listeners.add(received::add);

        ProjectAcceptanceDTO accepted = accept(detail);

        assertEquals("ACCEPTED", accepted.result());
        assertEquals(2, received.size());
        assertEquals(effective, received.getFirst().effectiveMillis(), "实时回调带上同样的有效毫秒");
        assertEquals(0L, received.get(1).effectiveMillis());
        assertEquals(received.stream().map(PluginProjectCheckInReward::effectiveMillis).toList(),
                rewards.acceptedCheckIns(accepted.createdAt(), 1, 50).stream()
                        .map(PluginProjectCheckInReward::effectiveMillis).toList(),
                "实时回调与拉取的有效毫秒逐个一致");
    }

    /** sinceAcceptedAt 是含下界；排序为 acceptedAt 升序、同时间按 checkInId 升序。 */
    @Test
    void acceptedCheckInsAppliesInclusiveSinceAndStableOrder() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail early = reviewingDetail(project, "早验收");
        ProjectWorkDetail late = reviewingDetail(project, "晚验收");
        checkIn("c003", project, early.id(), USER, 10);
        checkIn("c001", project, early.id(), USER, 11);
        checkIn("c002", project, early.id(), USER, 12);
        checkIn("c004", project, late.id(), USER, 13);
        acceptance("a001", project, early.id(), 1_000L);
        acceptance("a002", project, late.id(), 2_000L);

        assertEquals(List.of("c001", "c002", "c003", "c004"), ids(rewards.acceptedCheckIns(0, 1, 50)),
                "同一验收时间的多条打卡按 id 升序，整体按验收时间升序");
        assertEquals(List.of("c004"), ids(rewards.acceptedCheckIns(2_000L, 1, 50)),
                "下界是含的：等于下界的记录仍然返回");
        assertEquals(List.of("c001", "c002", "c003", "c004"), ids(rewards.acceptedCheckIns(1_000L, 1, 50)));
        assertEquals(List.of("c004"), ids(rewards.acceptedCheckIns(1_001L, 1, 50)),
                "严格大于早批时间的记录只剩晚批");
    }

    /** 分页跨页拼接不重不漏，空页返回空列表，排序在分页之前生效。 */
    @Test
    void acceptedCheckInsPaginatesWithoutDuplicates() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail accepted = reviewingDetail(project, "铺设石砖路");
        for (int index = 1; index <= 5; index++) {
            checkIn("c00" + index, project, accepted.id(), USER, index);
        }
        acceptance("a001", project, accepted.id(), 1_000L);

        List<String> paged = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            paged.addAll(ids(rewards.acceptedCheckIns(0, page, 2)));
        }

        assertEquals(List.of("c001", "c002", "c003", "c004", "c005"), paged, "跨页拼接等于全量且无重复");
        assertTrue(rewards.acceptedCheckIns(0, 4, 2).isEmpty(), "越界页返回空列表");
        assertEquals(List.of("c005"), ids(rewards.acceptedCheckIns(0, 5, 1)), "page/size 在排序之后生效");
    }

    /** 同一打卡经历多轮验收（含被驳回）只返回一条，acceptedAt 取最后一次验收通过的时间。 */
    @Test
    void acceptedCheckInsKeepsLastAcceptancePerCheckIn() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail detail = reviewingDetail(project, "铺设石砖路");
        checkIn("c001", project, detail.id(), USER, 10);
        acceptance("a001", project, detail.id(), 500L);
        repository.saveAcceptanceRecord(new ProjectAcceptanceRecord("a002", project.id(), detail.id(), ACCEPTOR,
                ProjectAcceptanceResult.REJECTED, "REVIEWING", "REPAIRING", "证据不足", 1_200L));
        // 退回返工后重新提交并通过
        documentRepository.saveDetail(documentRepository.findDetail(detail.id()).orElseThrow()
                .accept("REPAIRING")
                .submitAcceptance("REVIEWING", "已返工", List.of(new ProjectFileEvidence("k/again", "证据.png", "image/png", 12, true))));
        acceptance("a003", project, detail.id(), 1_500L);

        List<PluginProjectCheckInReward> rows = rewards.acceptedCheckIns(0, 1, 50);

        assertEquals(1, rows.size(), "同一打卡只返回一条");
        assertEquals(1_500L, rows.getFirst().acceptedAt(), "取最后一次验收通过的时间，不是第一次");
        assertEquals(List.of("c001"), ids(rewards.acceptedCheckIns(800L, 1, 50)),
                "下界按最后一次验收通过时间判断");
    }

    /**
     * 被驳回的打卡不算这一次打卡：既不进拉取结果，也不会被实时回调。
     *
     * <p>驳回 = 证据不成立，与该插件自身既有语义一致（被驳回后可以重新打卡）；拉取与回调两条路径同口径。</p>
     */
    @Test
    void rejectedCheckInsAreNeitherListedNorDispatched() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail detail = reviewingDetail(project, "铺设石砖路");
        checkIn("c001", project, detail.id(), USER, 10);
        rejectedCheckIn("c002", project, detail.id(), OTHER_USER, 11);
        checkIn("c003", project, detail.id(), USER, 12);
        List<PluginProjectCheckInReward> received = new ArrayList<>();
        listeners.add(received::add);

        ProjectAcceptanceDTO accepted = accept(detail);

        assertEquals("ACCEPTED", accepted.result());
        assertEquals(List.of("c001", "c003"), ids(rewards.acceptedCheckIns(0, 1, 50)),
                "被驳回的打卡不出现在 acceptedCheckIns");
        assertEquals(List.of("c001", "c003"), ids(received), "被驳回的打卡不会被实时回调");
    }

    /** projects() 返回全部项目的最小视图（含停用项目）。 */
    @Test
    void projectsReturnsEveryProjectIncludingDisabled() {
        ProjectProgressProject enabled = project("古城改造", true);
        ProjectProgressProject disabled = project("已封存项目", false);

        List<PluginProjectSummary> summaries = rewards.projects().stream()
                .sorted(Comparator.comparing(PluginProjectSummary::id))
                .toList();

        assertEquals(2, summaries.size());
        assertEquals(List.of(enabled.id(), disabled.id()).stream().sorted().toList(),
                summaries.stream().map(PluginProjectSummary::id).toList());
        assertEquals(1, summaries.stream().filter(PluginProjectSummary::enabled).count());
        assertEquals(1, summaries.stream().filter(summary -> !summary.enabled()).count());
    }

    // ------------------------------------------------------------------ 实时回调

    /** 验收通过会回调已注册的监听者，每条打卡一次，且与拉取口径一致。 */
    @Test
    void acceptNotifiesRegisteredListenerOncePerCheckIn() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail detail = reviewingDetail(project, "铺设石砖路");
        checkIn("c001", project, detail.id(), USER, 10);
        checkIn("c002", project, detail.id(), OTHER_USER, 11);
        checkIn("c003", project, detail.id(), USER, 12);
        List<PluginProjectCheckInReward> received = new ArrayList<>();
        listeners.add(received::add);

        ProjectAcceptanceDTO accepted = accept(detail);

        assertEquals("ACCEPTED", accepted.result());
        assertEquals("DONE", accepted.toStatusCode());
        assertEquals(List.of("c001", "c002", "c003"), ids(received), "每条打卡各回调一次，按打卡 id 升序");
        assertEquals(3, received.stream().map(PluginProjectCheckInReward::checkInId).distinct().count());
        received.forEach(reward -> {
            assertEquals(detail.id(), reward.detailId());
            assertEquals("铺设石砖路", reward.detailTitle());
            assertEquals(project.id(), reward.projectId());
            assertEquals(accepted.createdAt(), reward.acceptedAt(), "回调的 acceptedAt 就是本次验收通过时间");
            assertEquals(ACCEPTOR, reward.acceptedBy());
        });
        // 推送与拉取同口径：验收后可拉到的正是刚回调的那三条
        assertEquals(ids(received), ids(rewards.acceptedCheckIns(accepted.createdAt(), 1, 50)));
        assertEquals(1, checkInReads.get(), "回调按细节一次性批量读取打卡记录，不逐条读");
    }

    /** 监听者抛异常（含 RuntimeException 与 LinkageError）不影响验收结果，也不影响其他监听者。 */
    @Test
    void listenerFailureIsIsolatedFromAcceptanceAndOtherListeners() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail detail = reviewingDetail(project, "铺设石砖路");
        checkIn("c001", project, detail.id(), USER, 10);
        checkIn("c002", project, detail.id(), OTHER_USER, 11);
        List<PluginProjectCheckInReward> received = new ArrayList<>();
        listeners.add(reward -> {
            throw new IllegalStateException("消费方发放失败");
        });
        listeners.add(reward -> {
            throw new NoClassDefFoundError("消费方依赖缺失");
        });
        listeners.add(received::add);

        ProjectAcceptanceDTO accepted = accept(detail);

        assertEquals("ACCEPTED", accepted.result(), "监听者异常不影响验收通过结果");
        assertEquals(2, received.size(), "排在异常监听者之后的监听者照常收到回调");
        assertEquals("DONE", documentRepository.findDetail(detail.id()).orElseThrow().statusCode(), "验收结果已正常落库");
        assertEquals(1, repository.listAcceptanceRecords(detail.id(), 1, 50).stream()
                .filter(record -> record.result() == ProjectAcceptanceResult.ACCEPTED).count(), "验收记录也已落库");
    }

    /** 没有监听者时验收通过正常完成，且不会为回调做任何额外读取。 */
    @Test
    void acceptWithoutListenersSkipsCheckInReads() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail detail = reviewingDetail(project, "铺设石砖路");
        checkIn("c001", project, detail.id(), USER, 10);

        ProjectAcceptanceDTO accepted = accept(detail);

        assertEquals("ACCEPTED", accepted.result());
        assertEquals(0, checkInReads.get(), "没有监听者时不读打卡记录（不产生额外开销）");
        assertEquals("DONE", documentRepository.findDetail(detail.id()).orElseThrow().statusCode());
    }

    /** 连扩展点本身都读不到时，验收照样完成（回调是可选的尽力而为行为）。 */
    @Test
    void listenerLookupFailureDoesNotBreakAcceptance() {
        ProjectProgressProject project = project("古城改造", true);
        ProjectWorkDetail detail = reviewingDetail(project, "铺设石砖路");
        checkIn("c001", project, detail.id(), USER, 10);
        PluginContext brokenContext = (PluginContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginContext.class},
                (proxy, method, args) -> {
                    if ("extensions".equals(method.getName())) {
                        throw new IllegalStateException("扩展点不可用");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ProjectProgressAppService tolerant = new ProjectProgressAppService(repository, FakePluginContext.fileStore(), null,
                brokenContext);

        ProjectAcceptanceDTO accepted = tolerant.accept(detail.id(), new ProjectProgressAcceptanceCmd("通过", null), ACCEPTOR);

        assertEquals("ACCEPTED", accepted.result());
        assertEquals(0, checkInReads.get(), "读不到监听者时同样不读打卡记录");
    }

    // ------------------------------------------------------------------ 工具

    private ProjectAcceptanceDTO accept(ProjectWorkDetail detail) {
        assertThrows(IllegalArgumentException.class,
                () -> appService.accept(detail.id(), new ProjectProgressAcceptanceCmd("通过", null), USER),
                "非验收人不能验收（既有前置校验未被破坏）");
        return appService.accept(detail.id(), new ProjectProgressAcceptanceCmd("通过", null), ACCEPTOR);
    }

    private ProjectProgressProject project(String name, boolean enabled) {
        return repository.saveProject(ProjectProgressProject.create(name, "", List.of(ACCEPTOR),
                List.of(USER, OTHER_USER, ACCEPTOR), ProjectProgressProject.defaultStatuses(), "TODO", "DONE",
                "REPAIRING", 0, List.of(ProjectCheckInType.IMAGE), ProjectMinecraftPolicy.disabled(), null, "", enabled));
    }

    /** 已提交验收、等待验收的细节。 */
    private ProjectWorkDetail reviewingDetail(ProjectProgressProject project, String title) {
        ProjectWorkDetail detail = ProjectWorkDetail.create(project.id(), title, "", "TODO",
                ProjectAssignmentMode.CLAIM, 1, List.of(), List.of(USER), List.of(ACCEPTOR), null);
        return repository.saveDetail(detail.publish(List.of(USER)).submitAcceptance("REVIEWING", "已提交验收",
                List.of(new ProjectFileEvidence("k/" + title, "证据.png", "image/png", 12, true))));
    }

    private ProjectCheckInRecord checkIn(String id, ProjectProgressProject project, String detailId, String userId, long createdAt) {
        return repository.saveCheckIn(new ProjectCheckInRecord(id, project.id(), detailId, userId, ProjectCheckInType.IMAGE,
                "打卡 " + id, List.of(), null, null, ProjectCheckInReviewStatus.APPROVED, "", null, createdAt));
    }

    /** Minecraft 在线时长打卡：证据里带窗口内的有效在线毫秒数。 */
    private ProjectCheckInRecord minecraftCheckIn(String id, ProjectProgressProject project, String detailId,
                                                 String userId, long createdAt, long effectiveMillis) {
        ProjectMinecraftEvidence evidence = new ProjectMinecraftEvidence("srv-1", "player-1", "Steve", "",
                effectiveMillis + 10 * 60_000L, 10 * 60_000L, effectiveMillis, 0L, 0L, List.of());
        return repository.saveCheckIn(new ProjectCheckInRecord(id, project.id(), detailId, userId,
                ProjectCheckInType.MINECRAFT_ONLINE, "在线时长打卡 " + id, List.of(), null, evidence,
                ProjectCheckInReviewStatus.APPROVED, "", null, createdAt));
    }

    /** 旧数据形态：MC 打卡但证据缺失（没有 effectiveOnlineMillis）。 */
    private ProjectCheckInRecord legacyMinecraftCheckIn(String id, ProjectProgressProject project, String detailId,
                                                        String userId, long createdAt) {
        return repository.saveCheckIn(new ProjectCheckInRecord(id, project.id(), detailId, userId,
                ProjectCheckInType.MINECRAFT_ONLINE, "旧在线时长打卡 " + id, List.of(), null, null,
                ProjectCheckInReviewStatus.APPROVED, "", null, createdAt));
    }

    /** 已被验收人驳回的打卡：证据不成立，不算这次打卡。 */
    private ProjectCheckInRecord rejectedCheckIn(String id, ProjectProgressProject project, String detailId, String userId, long createdAt) {
        return repository.saveCheckIn(new ProjectCheckInRecord(id, project.id(), detailId, userId, ProjectCheckInType.IMAGE,
                "打卡 " + id, List.of(), null, null, ProjectCheckInReviewStatus.REJECTED, ACCEPTOR, 1_500L, createdAt));
    }

    private ProjectAcceptanceRecord acceptance(String id, ProjectProgressProject project, String detailId, long createdAt) {
        return repository.saveAcceptanceRecord(new ProjectAcceptanceRecord(id, project.id(), detailId, ACCEPTOR,
                ProjectAcceptanceResult.ACCEPTED, "REVIEWING", "DONE", "通过", createdAt));
    }

    private List<String> ids(List<PluginProjectCheckInReward> rows) {
        return rows.stream().map(PluginProjectCheckInReward::checkInId).toList();
    }

    /** 统计 {@code listCheckIns} 调用次数的仓储代理，用来验证「没有监听者时不读取」。 */
    private static ProjectProgressRepository countingRepository(ProjectProgressRepository delegate, AtomicInteger checkInReads) {
        return (ProjectProgressRepository) Proxy.newProxyInstance(ProjectProgressRewardServiceTest.class.getClassLoader(),
                new Class<?>[]{ProjectProgressRepository.class},
                (proxy, method, args) -> {
                    if ("listCheckIns".equals(method.getName())) {
                        checkInReads.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getTargetException();
                    }
                });
    }
}
