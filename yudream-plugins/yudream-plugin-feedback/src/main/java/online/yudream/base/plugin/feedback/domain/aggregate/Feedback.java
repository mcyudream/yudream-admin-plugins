package online.yudream.base.plugin.feedback.domain.aggregate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackActivityAction;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackCommentRole;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackSeverity;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackStatus;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackType;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackActivity;
import online.yudream.base.plugin.feedback.domain.valobj.FeedbackComment;

/**
 * 问题反馈聚合。
 *
 * <p>一条反馈由三部分构成：描述内容（标题 / 详情 / 类型 / 严重程度 / 截图）、
 * 用户与管理员共同参与的处理线程（评论）以及只追加的处理留痕（活动）。
 * 评论与活动都依附于本聚合，随反馈一起保存，不单独成为聚合根。
 *
 * <p>状态语义对齐 GitHub Issue：{@code open} 的两个状态表示仍在跟进，其余为已完结；
 * 管理员负责受理、指派与给出结论（已解决 / 不予处理），提交人只能在这两个结论状态下归档，
 * 管理员也可以在管理页列表上直接快捷归档。
 * 归档表示处理结束：归档后不能再修改内容，但仍可追加说明作为归档备注。
 *
 * <p>处理人是一个集合（对应 GitHub Issue 的多个 assignee）：管理员一旦对反馈做出受理动作，
 * 就会通过 {@link #claim(String, long)} 自动进入处理人名单，形成「谁处理过、谁就是处理人」的默认预期。
 */
public class Feedback {

    public static final int MAX_TITLE_LENGTH = 120;
    public static final int MAX_DESCRIPTION_LENGTH = 5000;
    public static final int MAX_ATTACHMENT_COUNT = 6;
    public static final int MAX_ASSIGNEE_COUNT = 8;
    /** 自动归档宽限期（天）：管理员给出结论后，提交人未主动归档则在此期限后由系统归档。 */
    public static final int ARCHIVE_GRACE_DAYS = 7;
    /** 系统留痕（自动归档）不归属于任何成员，用该哨兵值代替操作者 ID。 */
    public static final String SYSTEM_ACTOR = "system";

    private static final long ARCHIVE_GRACE_MILLIS = ARCHIVE_GRACE_DAYS * 86_400_000L;
    private static final int MAX_COMMENT_LENGTH = 2000;
    private static final int MAX_COMMENT_COUNT = 200;
    private static final int MAX_ACTIVITY_COUNT = 200;

    private final String id;
    private final String authorId;
    private String title;
    private String description;
    private FeedbackType type;
    private FeedbackSeverity severity;
    private FeedbackStatus status;
    /** 处理人（管理员）ID 集合；为空表示尚未指派。 */
    private final List<String> assigneeIds = new ArrayList<>();
    private final List<String> attachments = new ArrayList<>();
    private final List<FeedbackComment> comments = new ArrayList<>();
    private final List<FeedbackActivity> activities = new ArrayList<>();
    private final long createdAt;
    private long updatedAt;
    /** 最近一次进入已完结状态的时间（结论时间 / 归档时间）；回到未完结后清空。 */
    private Long closedAt;

    public Feedback(String id, String authorId, String title, String description, FeedbackType type,
                    FeedbackSeverity severity, FeedbackStatus status, List<String> assigneeIds,
                    List<String> attachments,
                    List<FeedbackComment> comments, List<FeedbackActivity> activities,
                    long createdAt, long updatedAt, Long closedAt) {
        this.id = id;
        this.authorId = authorId;
        this.title = normalizeTitle(title);
        this.description = normalizeDescription(description);
        this.type = type == null ? FeedbackType.OTHER : type;
        this.severity = severity == null ? FeedbackSeverity.NORMAL : severity;
        this.status = status == null ? FeedbackStatus.PENDING : status;
        this.assigneeIds.addAll(cleanIds(assigneeIds, MAX_ASSIGNEE_COUNT));
        this.attachments.addAll(cleanList(attachments, MAX_ATTACHMENT_COUNT));
        if (comments != null) {
            this.comments.addAll(comments);
        }
        if (activities != null) {
            this.activities.addAll(activities);
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.closedAt = closedAt;
    }

    /** 用户提交反馈：状态固定为待处理，归属只能由受信任身份传入。 */
    public static Feedback create(String id, String authorId, String title, String description,
                                  FeedbackType type, FeedbackSeverity severity,
                                  List<String> attachments, long now) {
        Feedback feedback = new Feedback(id, authorId, title, description, type, severity,
                FeedbackStatus.PENDING, List.of(), attachments, List.of(), List.of(), now, now, null);
        feedback.record(authorId, FeedbackActivityAction.SUBMIT, "", now);
        return feedback;
    }

    /**
     * 修改反馈内容；只在未完结时可改，避免已定论的历史被追溯篡改。
     *
     * <p>状态不做联动：内容修正不改变受理进度。
     */
    public void updateContent(String title, String description, FeedbackType type, FeedbackSeverity severity,
                              List<String> attachments, long now) {
        if (!open()) {
            throw new IllegalArgumentException("反馈已完结，无法再修改内容");
        }
        this.title = normalizeTitle(title);
        this.description = normalizeDescription(description);
        this.type = type == null ? FeedbackType.OTHER : type;
        this.severity = severity == null ? FeedbackSeverity.NORMAL : severity;
        this.attachments.clear();
        this.attachments.addAll(cleanList(attachments, MAX_ATTACHMENT_COUNT));
        record(authorId, FeedbackActivityAction.UPDATE, "", now);
    }

    /**
     * 追加一条评论。
     *
     * <p>评论 ID 由应用层生成；已完结反馈仍允许补充说明与内部备注，方便事后归档说明，
     * 但不再允许重新进入处理流程。
     */
    public FeedbackComment comment(String commentId, String commentAuthorId, FeedbackCommentRole role,
                                   String content, boolean internal, long now) {
        FeedbackComment comment = new FeedbackComment(commentId, commentAuthorId, role,
                normalizeComment(content), internal, now);
        if (comments.size() >= MAX_COMMENT_COUNT) {
            throw new IllegalArgumentException("单条反馈最多保留 " + MAX_COMMENT_COUNT + " 条评论");
        }
        comments.add(comment);
        if (internal) {
            record(commentAuthorId, FeedbackActivityAction.NOTE, "", now);
        }
        else {
            record(commentAuthorId, role == FeedbackCommentRole.ADMIN
                    ? FeedbackActivityAction.REPLY : FeedbackActivityAction.COMMENT, "", now);
        }
        return comment;
    }

    /** 流转状态；进入已完结状态记录结论时间，回到未完结则清空。 */
    public void changeStatus(FeedbackStatus next, String actorId, String note, long now) {
        FeedbackStatus target = next == null ? FeedbackStatus.PENDING : next;
        if (target == status) {
            throw new IllegalArgumentException("反馈已处于「" + target.label() + "」状态");
        }
        this.status = target;
        this.closedAt = target.open() ? null : now;
        record(actorId, FeedbackActivityAction.STATUS, "变更为「" + target.label() + "」" + suffix(note), now);
    }

    /**
     * 提交人归档：只在管理员给出结论（已解决 / 不予处理）之后允许。
     *
     * <p>归档是终态：归档后不再提供重新打开，也不能再修改内容，但允许继续追加说明，
     * 便于补一句归档备注。{@code detail} 用于区分主动归档与系统自动归档。
     */
    public void archive(String actorId, String detail, long now) {
        if (status == FeedbackStatus.ARCHIVED) {
            throw new IllegalArgumentException("反馈已归档");
        }
        if (!status.concluded()) {
            throw new IllegalArgumentException("管理员给出结论后才能归档，当前状态为「" + status.label() + "」");
        }
        this.status = FeedbackStatus.ARCHIVED;
        this.closedAt = now;
        record(actorId, FeedbackActivityAction.ARCHIVE, detail, now);
    }

    /**
     * 管理员直接归档：管理页列表里的「快捷归档」。
     *
     * <p>比提交人的 {@link #archive(String, String, long)} 宽松——管理员本来就能在详情页把状态
     * 直接流转为「已归档」，这里只是让同一个动作在列表上少点两次，因此不要求先给出结论；
     * 已归档的反馈再次归档仍然报错。
     */
    public void archiveByAdmin(String actorId, long now) {
        if (status == FeedbackStatus.ARCHIVED) {
            throw new IllegalArgumentException("反馈已归档");
        }
        this.status = FeedbackStatus.ARCHIVED;
        this.closedAt = now;
        record(actorId, FeedbackActivityAction.ARCHIVE, "由管理员归档", now);
    }

    /**
     * 是否已到自动归档时间：已给出结论，且结论时间距今超过宽限期。
     *
     * <p>历史数据可能缺少结论时间，此时不参与自动归档——改用 {@code updatedAt} 推断会把
     * 仍在沟通中的反馈误归档。
     */
    public boolean archiveExpired(long now) {
        return status.concluded() && closedAt != null && now - closedAt >= ARCHIVE_GRACE_MILLIS;
    }

    /** 自动归档时间：已给出结论时为结论时间 + 宽限期，其余为 {@code null}。 */
    public Long autoArchiveAt() {
        return status.concluded() && closedAt != null ? closedAt + ARCHIVE_GRACE_MILLIS : null;
    }

    /**
     * 受理动作的默认副作用：把操作者本人加入处理人名单。
     *
     * <p>管理员的每一次更新（回复、备注、流转状态、调整严重程度）都意味着「这条反馈由我经手」，
     * 因此默认把自己登记为处理人，避免受理之后处理人一栏仍是空的；已经在名单里的不重复登记。
     * 名单达到上限时静默跳过——这是附带动作，不应阻断主要的受理操作。
     *
     * @return 本次是否新增了处理人，调用方可用于提示
     */
    public boolean claim(String actorId, long now) {
        String target = cleanId(actorId);
        if (target == null || assigneeIds.contains(target) || assigneeIds.size() >= MAX_ASSIGNEE_COUNT) {
            return false;
        }
        assigneeIds.add(target);
        record(actorId, FeedbackActivityAction.ASSIGN, "自动加入处理人", now);
        return true;
    }

    /**
     * 整体替换处理人名单（管理员显式维护）。
     *
     * <p>{@code names} 只用于留痕文案，避免把内部 ID 写进处理记录里给用户看。
     */
    public void replaceAssignees(List<String> next, List<String> names, String actorId, long now) {
        List<String> cleaned = cleanIds(next, MAX_ASSIGNEE_COUNT);
        if (cleaned.equals(assigneeIds)) {
            throw new IllegalArgumentException("处理人没有变化");
        }
        assigneeIds.clear();
        assigneeIds.addAll(cleaned);
        String detail = names == null || names.isEmpty()
                ? "已清空处理人"
                : "处理人：" + String.join("、", names);
        record(actorId, cleaned.isEmpty() ? FeedbackActivityAction.UNASSIGN : FeedbackActivityAction.ASSIGN,
                detail, now);
    }

    /** 调整严重程度；由管理员在受理时修正用户自评的等级。 */
    public void changeSeverity(FeedbackSeverity next, String actorId, long now) {
        FeedbackSeverity target = next == null ? FeedbackSeverity.NORMAL : next;
        if (target == severity) {
            throw new IllegalArgumentException("严重程度已是「" + target.label() + "」");
        }
        this.severity = target;
        record(actorId, FeedbackActivityAction.SEVERITY, "调整为「" + target.label() + "」", now);
    }

    /** 是否未完结（对应 GitHub 的 open）。 */
    public boolean open() {
        return status.open();
    }

    public boolean ownedBy(String userId) {
        return userId != null && !userId.isBlank() && userId.equals(authorId);
    }

    /** 活动条数对外暴露用于展示，不做逻辑判断。 */
    public int activityCount() {
        return activities.size();
    }

    private void record(String actorId, FeedbackActivityAction action, String detail, long now) {
        if (activities.size() >= MAX_ACTIVITY_COUNT) {
            activities.remove(0);
        }
        activities.add(FeedbackActivity.of(actorId, action, detail, now));
        this.updatedAt = now;
    }

    private static String suffix(String note) {
        String value = note == null ? "" : note.trim();
        return value.isEmpty() ? "" : "：" + value;
    }

    private static List<String> cleanIds(List<String> raw, int limit) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                String id = cleanId(item);
                if (id == null) {
                    continue;
                }
                ids.add(id);
                if (ids.size() >= limit) {
                    break;
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static List<String> cleanList(List<String> raw, int limit) {
        List<String> result = new ArrayList<>();
        if (raw != null) {
            for (String item : raw) {
                String value = item == null ? "" : item.trim();
                if (value.isEmpty() || result.contains(value)) {
                    continue;
                }
                result.add(value);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private static String normalizeTitle(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("反馈标题不能为空");
        }
        if (value.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("反馈标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }
        return value;
    }

    private static String normalizeDescription(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("请填写问题描述");
        }
        if (value.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("问题描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        }
        return value;
    }

    private static String normalizeComment(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("评论内容不能为空");
        }
        if (value.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("评论内容不能超过 " + MAX_COMMENT_LENGTH + " 个字符");
        }
        return value;
    }

    private static String cleanId(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.isEmpty() ? null : value;
    }

    public String id() {
        return id;
    }

    public String authorId() {
        return authorId;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public FeedbackType type() {
        return type;
    }

    public FeedbackSeverity severity() {
        return severity;
    }

    public FeedbackStatus status() {
        return status;
    }

    public List<String> assigneeIds() {
        return List.copyOf(assigneeIds);
    }

    public List<String> attachments() {
        return List.copyOf(attachments);
    }

    public List<FeedbackComment> comments() {
        return List.copyOf(comments);
    }

    public List<FeedbackActivity> activities() {
        return List.copyOf(activities);
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public Long closedAt() {
        return closedAt;
    }
}
