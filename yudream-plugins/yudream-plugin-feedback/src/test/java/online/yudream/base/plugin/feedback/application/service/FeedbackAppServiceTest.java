package online.yudream.base.plugin.feedback.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import online.yudream.base.plugin.feedback.application.cmd.FeedbackSubmitCmd;
import online.yudream.base.plugin.feedback.application.dto.AssignableUsersDTO;
import online.yudream.base.plugin.feedback.application.dto.FeedbackDTO;
import online.yudream.base.plugin.feedback.application.dto.FeedbackPageDTO;
import online.yudream.base.plugin.feedback.domain.aggregate.Feedback;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackStatus;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackQuery;
import online.yudream.base.plugin.feedback.infrastructure.repository.FeedbackDocumentRepository;
import online.yudream.base.plugin.feedback.support.FakeUserService;
import online.yudream.base.plugin.feedback.support.InMemoryDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 管理端受理动作的副作用与处理人筛选口径。
 *
 * <p>这里的重点是「谁更新、谁就是处理人」这条链路上有没有真的接上：
 * 领域方法 {@code claim} 已单测覆盖，但必须在每个管理端用例里都被调用到才有意义。
 */
class FeedbackAppServiceTest {

    private static final String AUTHOR = "1001";
    private static final String ADMIN_7 = "7";
    private static final String ADMIN_8 = "8";

    private FeedbackDocumentRepository repository;
    private FeedbackAppService service;

    @BeforeEach
    void setUp() {
        repository = new FeedbackDocumentRepository(new InMemoryDocumentStore());
        service = new FeedbackAppService(repository,
                new FakeUserService().add(7, "admin7", "陈七").add(8, "admin8", "王八"));
    }

    private String submit() {
        FeedbackDTO created = service.submit(AUTHOR, FeedbackSubmitCmd.of("登录报错", "点登录后白屏",
                "bug", "high", List.of()));
        return created.id();
    }

    private FeedbackDTO reload(String id) {
        return service.adminFeedback(id);
    }

    @Test
    void 可指派处理人来自宿主账号目录() {
        AssignableUsersDTO result = service.searchAssignableUsers("", 1, 10);

        assertTrue(result.available(), "SPI 账号目录可用时应返回候选");
        assertEquals(2, result.records().size());
        assertTrue(result.notice().contains("未按反馈管理权限预筛"),
                "必须提示候选没有按管理权限过滤，管理员才能自行确认");
    }

    @Test
    void 宿主账号目录不可用时拒绝指派处理人() {
        FeedbackAppService withoutDirectory = new FeedbackAppService(repository, null);
        AssignableUsersDTO result = withoutDirectory.searchAssignableUsers("", 1, 10);

        assertFalse(result.available(), "账号目录不可用时必须拒绝指派");
        assertTrue(result.records().isEmpty());
        assertNotNull(result.notice(), "要给管理员一个可读原因，否则只看到空列表");
    }

    @Test
    void 官方回复会把操作者加入处理人() {
        String id = submit();
        FeedbackDTO dto = service.adminReply(id, ADMIN_7, "已定位到是缓存问题");
        assertEquals(List.of(ADMIN_7), dto.assigneeIds());
        assertEquals(1, dto.assignees().size());
        assertEquals("陈七", dto.assignees().get(0).name(), "处理人应带出昵称");
    }

    @Test
    void 内部备注会把操作者加入处理人() {
        String id = submit();
        assertEquals(List.of(ADMIN_7), service.adminNote(id, ADMIN_7, "先记一笔").assigneeIds());
    }

    @Test
    void 状态流转会把操作者加入处理人() {
        String id = submit();
        assertEquals(List.of(ADMIN_7),
                service.adminChangeStatus(id, ADMIN_7, "processing", "开始复现").assigneeIds());
    }

    @Test
    void 调整严重程度会把操作者加入处理人() {
        String id = submit();
        assertEquals(List.of(ADMIN_7), service.adminUpdateSeverity(id, ADMIN_7, "critical").assigneeIds());
    }

    @Test
    void 多个管理员先后受理会形成多人处理人名单() {
        String id = submit();
        service.adminReply(id, ADMIN_7, "我先看看");
        FeedbackDTO dto = service.adminChangeStatus(id, ADMIN_8, "resolved", "已修复");
        assertEquals(List.of(ADMIN_7, ADMIN_8), dto.assigneeIds());
    }

    @Test
    void 显式保存处理人不把自己追加进名单() {
        String id = submit();
        FeedbackDTO dto = service.adminAssign(id, ADMIN_7, List.of(ADMIN_8));
        assertEquals(List.of(ADMIN_8), dto.assigneeIds(), "显式维护名单不应再自动加入操作者");
    }

    @Test
    void 显式保存处理人可以清空名单() {
        String id = submit();
        service.adminReply(id, ADMIN_7, "接手");
        FeedbackDTO dto = service.adminAssign(id, ADMIN_7, List.of());
        assertTrue(dto.assigneeIds().isEmpty());
    }

    @Test
    void 指派不存在的成员会被拒绝() {
        String id = submit();
        assertThrows(IllegalArgumentException.class,
                () -> service.adminAssign(id, ADMIN_7, List.of("999999")));
    }

    @Test
    void 处理人数量超过上限会被拒绝() {
        String id = submit();
        List<String> tooMany = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThrows(IllegalArgumentException.class,
                () -> service.adminAssign(id, ADMIN_7, tooMany));
    }

    @Test
    void 按处理人筛选取名单包含语义() {
        String first = submit();
        String second = submit();
        service.adminReply(first, ADMIN_7, "我处理");
        service.adminReply(second, ADMIN_8, "我处理");

        FeedbackPageDTO<FeedbackDTO> page = service.adminPageFeedbacks(new FeedbackQuery().assigneeId(ADMIN_7));
        assertEquals(1, page.total());
        assertEquals(first, page.records().get(0).id());
    }

    @Test
    void 待指派筛选只看完全没有处理人的反馈() {
        String assigned = submit();
        String pending = submit();
        service.adminReply(assigned, ADMIN_7, "我处理");

        FeedbackPageDTO<FeedbackDTO> page = service.adminPageFeedbacks(new FeedbackQuery().unassignedOnly(true));
        assertEquals(1, page.total());
        assertEquals(pending, page.records().get(0).id());
    }

    @Test
    void 用户端不下发内部备注() {
        String id = submit();
        service.adminNote(id, ADMIN_7, "内部排查记录");
        FeedbackDTO mine = service.myFeedback(AUTHOR, id);
        assertTrue(mine.comments().isEmpty(), "内部备注不能出现在用户端响应里");
        assertTrue(mine.activities().stream().noneMatch(item -> "note".equals(item.action())),
                "内部备注对应的留痕也不能下发");
        assertEquals(1, reload(id).comments().size(), "管理端仍应看到这条备注");
    }

    @Test
    void 用户端数据范围不会因管理端筛选而扩大() {
        String mine = submit();
        FeedbackPageDTO<FeedbackDTO> page = service.listMyFeedbacks(AUTHOR,
                new FeedbackQuery().authorId("9999").keyword("登录"));
        assertEquals(1, page.total(), "作者筛选必须被收敛回当前登录者");
        assertEquals(mine, page.records().get(0).id());
    }

    // ---------------------------------------------------------------- 归档与自动归档（1.1.0）

    /** 把一条反馈的结论时间改到指定时刻，用于构造「已过期」的自动归档场景。 */
    private void conclude(String id, String status, long concludedAt) {
        Feedback feedback = repository.findById(id).orElseThrow();
        feedback.changeStatus(FeedbackStatus.of(status), ADMIN_7, "结论", concludedAt);
        repository.save(feedback);
    }

    @Test
    void 给出结论后提交人可以归档且归档为终态() {
        String id = submit();
        service.adminChangeStatus(id, ADMIN_7, "resolved", "已修复");

        FeedbackDTO concluded = service.myFeedback(AUTHOR, id);
        assertEquals("resolved", concluded.status(), "结论后停在已解决，等提交人决定");
        assertTrue(concluded.canArchive(), "结论后应出现归档入口");

        FeedbackDTO archived = service.archiveMyFeedback(AUTHOR, id);
        assertEquals("archived", archived.status());
        assertFalse(archived.canArchive(), "归档后不能再归档");
    }

    @Test
    void 未给出结论时不能归档() {
        String id = submit();
        assertFalse(service.myFeedback(AUTHOR, id).canArchive(), "待处理不提供归档入口");
        assertThrows(IllegalArgumentException.class, () -> service.archiveMyFeedback(AUTHOR, id));
    }

    @Test
    void 管理端可以直接归档尚未给出结论的反馈() {
        String id = submit();
        FeedbackDTO archived = service.adminArchiveFeedback(id, ADMIN_7);
        assertEquals("archived", archived.status(), "快捷归档不要求先给出结论");
        assertTrue(archived.activities().stream()
                        .anyMatch(item -> "archive".equals(item.action()) && "由管理员归档".equals(item.detail())),
                "留痕应记为归档而不是普通状态变更");
        assertThrows(IllegalArgumentException.class, () -> service.adminArchiveFeedback(id, ADMIN_7),
                "已归档的反馈不能再归档");
    }

    @Test
    void 结论满七天未归档会被系统自动归档() {
        String id = submit();
        long concludedAt = 1_700_000_000_000L;
        conclude(id, "resolved", concludedAt);

        long now = concludedAt + Feedback.ARCHIVE_GRACE_DAYS * 86_400_000L;
        assertEquals(1, service.autoArchiveExpired(now));

        FeedbackDTO dto = reload(id);
        assertEquals("archived", dto.status());
        assertTrue(dto.activities().stream()
                        .anyMatch(item -> "archive".equals(item.action()) && "系统".equals(item.actorName())),
                "自动归档应留痕且操作者显示为「系统」，而不是空名");
    }

    @Test
    void 未满七天不会被自动归档() {
        String id = submit();
        long concludedAt = 1_700_000_000_000L;
        conclude(id, "rejected", concludedAt);

        long justBefore = concludedAt + Feedback.ARCHIVE_GRACE_DAYS * 86_400_000L - 1;
        assertEquals(0, service.autoArchiveExpired(justBefore));
        assertEquals("rejected", reload(id).status());
    }

    @Test
    void 未给出结论的反馈不会被自动归档() {
        String id = submit();
        assertEquals(0, service.autoArchiveExpired(System.currentTimeMillis() + 400L * 86_400_000L),
                "待处理无论过多久都不自动归档");
        assertEquals("pending", reload(id).status());
    }

    // ---------------------------------------------------------------- 受理自动转「处理中」（1.1.0）

    @Test
    void 官方回复会把待处理推进为处理中() {
        String id = submit();
        assertEquals("processing", service.adminReply(id, ADMIN_7, "已定位到是缓存问题").status());
    }

    @Test
    void 内部备注与调整严重程度同样推进状态() {
        assertEquals("processing", service.adminNote(submit(), ADMIN_7, "先记一笔").status());
        assertEquals("processing", service.adminUpdateSeverity(submit(), ADMIN_7, "critical").status());
    }

    @Test
    void 调整处理人也会把待处理推进为处理中() {
        String id = submit();
        FeedbackDTO dto = service.adminAssign(id, ADMIN_7, List.of(ADMIN_8));
        assertEquals("processing", dto.status());
        assertEquals(List.of(ADMIN_8), dto.assigneeIds(), "推进状态不应顺带把操作者塞进名单");
    }

    @Test
    void 结论状态不会被受理动作翻回处理中() {
        String id = submit();
        service.adminChangeStatus(id, ADMIN_7, "resolved", "已修复");
        assertEquals("resolved", service.adminReply(id, ADMIN_7, "补充一句").status(),
                "自动流转只针对待处理");
    }

    @Test
    void 显式把状态退回待处理不会被立刻翻回() {
        String id = submit();
        service.adminChangeStatus(id, ADMIN_7, "processing", "开始处理");
        assertEquals("pending", service.adminChangeStatus(id, ADMIN_7, "pending", "退回待处理").status(),
                "显式状态流转不参与自动联动，否则退回动作会被立刻覆盖");
    }

    @Test
    void 统计口径里的归档数随归档变化() {
        String id = submit();
        assertEquals(0, service.adminStats().archived());
        service.adminChangeStatus(id, ADMIN_7, "resolved", "已修复");
        service.archiveMyFeedback(AUTHOR, id);
        assertEquals(1, service.adminStats().archived());
        assertEquals(0, service.adminStats().open(), "归档不计入未完结");
    }

    // ---------------------------------------------------------------- 全部口径排除归档（1.4.0）

    @Test
    void 全部统计不含已归档() {
        String id = submit();
        assertEquals(1, service.adminStats().total(), "未归档时全部就是唯一那条");

        service.adminArchiveFeedback(id, ADMIN_7);

        assertEquals(0, service.adminStats().total(), "归档后不再计入全部");
        assertEquals(1, service.adminStats().archived(), "归档仍有自己的计数");
    }

    @Test
    void 未指定状态时列表默认不返回已归档() {
        String archived = submit();
        String active = submit();
        service.adminArchiveFeedback(archived, ADMIN_7);

        FeedbackPageDTO<FeedbackDTO> all = service.adminPageFeedbacks(new FeedbackQuery());
        assertEquals(1, all.total(), "默认视图只给仍在流转的反馈");
        assertEquals(active, all.records().get(0).id());

        FeedbackPageDTO<FeedbackDTO> onlyArchived = service.adminPageFeedbacks(
                new FeedbackQuery().status(FeedbackStatus.ARCHIVED));
        assertEquals(1, onlyArchived.total(), "显式选「已归档」必须还能查到");
        assertEquals(archived, onlyArchived.records().get(0).id());
    }

    @Test
    void 用户端的全部列表与统计同样不含已归档() {
        String mine = submit();
        service.adminChangeStatus(mine, ADMIN_7, "resolved", "已修复");
        service.archiveMyFeedback(AUTHOR, mine);

        assertEquals(1, service.myStats(AUTHOR).archived());
        assertEquals(0, service.myStats(AUTHOR).total(), "本人统计的「全部」也不含归档");
        assertEquals(0, service.listMyFeedbacks(AUTHOR, new FeedbackQuery()).total(),
                "本人默认列表同样不返回归档");
    }
}
