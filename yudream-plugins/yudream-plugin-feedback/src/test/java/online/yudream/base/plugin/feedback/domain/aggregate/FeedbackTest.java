package online.yudream.base.plugin.feedback.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackActivityAction;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackSeverity;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackStatus;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackType;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackActivity;
import org.junit.jupiter.api.Test;

/** 处理人集合与受理留痕的领域行为。 */
class FeedbackTest {

    private static final String AUTHOR = "1001";
    private static final long NOW = 1_700_000_000_000L;

    private static Feedback newFeedback() {
        return Feedback.create("fb-1", AUTHOR, "标题", "描述", FeedbackType.BUG,
                FeedbackSeverity.NORMAL, List.of(), NOW);
    }

    @Test
    void 提交后处理人为空() {
        Feedback feedback = newFeedback();
        assertTrue(feedback.assigneeIds().isEmpty(), "刚提交的反馈不应有处理人");
        assertEquals(1, feedback.activities().size(), "提交应产生一条留痕");
        assertEquals(FeedbackActivityAction.SUBMIT, feedback.activities().get(0).action());
    }

    @Test
    void claim把操作者加入处理人并留痕() {
        Feedback feedback = newFeedback();
        assertTrue(feedback.claim("7", NOW + 10));
        assertEquals(List.of("7"), feedback.assigneeIds());
        assertEquals(2, feedback.activities().size());
        FeedbackActivity assign = feedback.activities().get(1);
        assertEquals(FeedbackActivityAction.ASSIGN, assign.action());
        assertEquals("7", assign.actorId());
        assertEquals("自动加入处理人", assign.detail());
    }

    @Test
    void claim重复调用只登记一次() {
        Feedback feedback = newFeedback();
        assertTrue(feedback.claim("7", NOW + 10));
        assertFalse(feedback.claim("7", NOW + 20), "已在名单内应返回 false");
        assertEquals(List.of("7"), feedback.assigneeIds());
        assertEquals(2, feedback.activities().size(), "重复 claim 不应重复留痕");
    }

    @Test
    void claim支持多人协作但不超过上限() {
        Feedback feedback = newFeedback();
        IntStream.rangeClosed(1, Feedback.MAX_ASSIGNEE_COUNT)
                .forEach(index -> assertTrue(feedback.claim(String.valueOf(index), NOW + index)));
        assertEquals(Feedback.MAX_ASSIGNEE_COUNT, feedback.assigneeIds().size());
        assertFalse(feedback.claim("999", NOW + 100), "超过上限应静默跳过");
        assertEquals(Feedback.MAX_ASSIGNEE_COUNT, feedback.assigneeIds().size());
    }

    @Test
    void claim对空操作者不生效() {
        Feedback feedback = newFeedback();
        assertFalse(feedback.claim("  ", NOW + 10));
        assertTrue(feedback.assigneeIds().isEmpty());
    }

    @Test
    void replaceAssignees整体替换并用姓名留痕() {
        Feedback feedback = newFeedback();
        feedback.claim("7", NOW + 10);
        feedback.replaceAssignees(List.of("8", "9"), List.of("张三", "李四"), "7", NOW + 20);
        assertEquals(List.of("8", "9"), feedback.assigneeIds());
        FeedbackActivity last = feedback.activities().get(feedback.activities().size() - 1);
        assertEquals(FeedbackActivityAction.ASSIGN, last.action());
        assertEquals("处理人：张三、李四", last.detail());
    }

    @Test
    void replaceAssignees去重并拒绝无变化() {
        Feedback feedback = newFeedback();
        feedback.replaceAssignees(List.of("8", "8", " "), List.of("张三"), "7", NOW + 20);
        assertEquals(List.of("8"), feedback.assigneeIds());
        assertThrows(IllegalArgumentException.class,
                () -> feedback.replaceAssignees(List.of("8"), List.of("张三"), "7", NOW + 30));
    }

    @Test
    void replaceAssignees允许清空并记取消指派() {
        Feedback feedback = newFeedback();
        feedback.claim("7", NOW + 10);
        feedback.replaceAssignees(List.of(), List.of(), "7", NOW + 20);
        assertTrue(feedback.assigneeIds().isEmpty());
        FeedbackActivity last = feedback.activities().get(feedback.activities().size() - 1);
        assertEquals(FeedbackActivityAction.UNASSIGN, last.action());
        assertEquals("已清空处理人", last.detail());
    }

    @Test
    void 同一时刻的自动加入与状态变更共享时间戳() {
        Feedback feedback = newFeedback();
        long sameInstant = NOW + 50;
        feedback.claim("7", sameInstant);
        feedback.changeStatus(FeedbackStatus.PROCESSING, "7", "先接手，正在复现", sameInstant);

        List<FeedbackActivity> tail = feedback.activities().subList(1, 3);
        assertEquals(2, tail.size());
        assertTrue(tail.stream().allMatch(item -> item.actorId().equals("7")),
                "两条留痕应属于同一操作者");
        assertTrue(tail.stream().allMatch(item -> item.createdAt() == sameInstant),
                "同一时刻是前端把系统信息合并进同一气泡的依据");
    }

    // ---------------------------------------------------------------- 归档（1.1.0）

    @Test
    void 归档只在管理员给出结论后允许() {
        Feedback feedback = newFeedback();
        assertThrows(IllegalArgumentException.class, () -> feedback.archive(AUTHOR, "", NOW + 10),
                "待处理不能归档");
        feedback.changeStatus(FeedbackStatus.PROCESSING, "7", "开始处理", NOW + 20);
        assertThrows(IllegalArgumentException.class, () -> feedback.archive(AUTHOR, "", NOW + 30),
                "处理中同样不能归档");
    }

    @Test
    void 归档进入终态并记录归档时间与留痕() {
        Feedback feedback = newFeedback();
        feedback.changeStatus(FeedbackStatus.RESOLVED, "7", "已修复", NOW + 10);
        long archivedAt = NOW + 100;
        feedback.archive(AUTHOR, "", archivedAt);

        assertEquals(FeedbackStatus.ARCHIVED, feedback.status());
        assertEquals(archivedAt, feedback.closedAt(), "归档时间写进 closedAt");
        assertFalse(feedback.open(), "归档属于已完结");
        FeedbackActivity last = feedback.activities().get(feedback.activities().size() - 1);
        assertEquals(FeedbackActivityAction.ARCHIVE, last.action());
        assertEquals(AUTHOR, last.actorId());
    }

    @Test
    void 已归档不能重复归档() {
        Feedback feedback = newFeedback();
        feedback.changeStatus(FeedbackStatus.REJECTED, "7", "不属于缺陷", NOW + 10);
        feedback.archive(AUTHOR, "", NOW + 20);
        assertThrows(IllegalArgumentException.class, () -> feedback.archive(AUTHOR, "", NOW + 30));
    }

    @Test
    void 自动归档时间与过期判定以结论时间为起点() {
        Feedback feedback = newFeedback();
        assertNull(feedback.autoArchiveAt(), "未给出结论时没有自动归档时间");
        assertFalse(feedback.archiveExpired(NOW + 999_999_999L), "未完结永远不算过期");

        long concludedAt = NOW + 10;
        feedback.changeStatus(FeedbackStatus.RESOLVED, "7", "已修复", concludedAt);
        long deadline = concludedAt + Feedback.ARCHIVE_GRACE_DAYS * 86_400_000L;
        assertEquals(deadline, feedback.autoArchiveAt());
        assertFalse(feedback.archiveExpired(deadline - 1), "差 1 毫秒不算过期");
        assertTrue(feedback.archiveExpired(deadline), "整 7 天即过期");
    }

    @Test
    void 回到未完结会清空结论时间与自动归档时间() {
        Feedback feedback = newFeedback();
        feedback.changeStatus(FeedbackStatus.RESOLVED, "7", "已修复", NOW + 10);
        feedback.changeStatus(FeedbackStatus.PENDING, AUTHOR, "由提交人重新打开", NOW + 20);
        assertNull(feedback.closedAt());
        assertNull(feedback.autoArchiveAt());
    }

    @Test
    void 状态枚举认识历史归档名称closed() {
        assertEquals(FeedbackStatus.ARCHIVED, FeedbackStatus.of("archived"));
        assertEquals(FeedbackStatus.ARCHIVED, FeedbackStatus.of("closed"), "1.0.0 的 closed 必须仍然可读");
        assertEquals(FeedbackStatus.ARCHIVED, FeedbackStatus.lenient("CLOSED"));
        assertEquals("已归档", FeedbackStatus.ARCHIVED.label());
        assertTrue(FeedbackStatus.RESOLVED.concluded());
        assertTrue(FeedbackStatus.REJECTED.concluded());
        assertFalse(FeedbackStatus.PENDING.concluded());
        assertFalse(FeedbackStatus.ARCHIVED.concluded(), "归档后不再是可归档的结论态");
        assertThrows(IllegalArgumentException.class, () -> FeedbackStatus.of("done"));
    }
}
