package online.yudream.base.plugin.feedback.domain.valobj;

import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackSeverity;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackStatus;
import online.yudream.base.plugin.feedback.domain.enumerate.FeedbackType;

/**
 * 反馈查询条件。
 *
 * <p>{@code authorId} 是数据范围而不是可选筛选：用户端用例会无条件覆盖为当前登录者，
 * 管理端用例才从查询参数取值，因此同一个字段不会成为越权入口。
 */
public class FeedbackQuery {

    private String keyword = "";
    /** 状态过滤；为 null 表示「全部」，按 {@link #allows(FeedbackStatus)} 排除已归档。 */
    private FeedbackStatus status;
    private FeedbackType type;
    private FeedbackSeverity severity;
    private String assigneeId = "";
    /** 只看未指派处理人的反馈。 */
    private Boolean unassignedOnly;
    /** 只看未完结反馈；缺省表示不过滤。 */
    private Boolean openOnly;
    private String authorId = "";
    /** 提交时间下限（毫秒，含）。 */
    private Long from;
    /** 提交时间上限（毫秒，含）。 */
    private Long to;
    private int page = 1;
    private int size = 10;

    public String keyword() {
        return keyword == null ? "" : keyword;
    }

    public FeedbackQuery keyword(String value) {
        this.keyword = value == null ? "" : value.trim();
        return this;
    }

    public boolean hasKeyword() {
        return !keyword().isEmpty();
    }

    public FeedbackStatus status() {
        return status;
    }

    public FeedbackQuery status(FeedbackStatus value) {
        this.status = value;
        return this;
    }

    /**
     * 状态过滤判定：未指定状态时默认排除已归档。
     *
     * <p>归档是「收起来」的终态，「全部反馈」这一档只反映仍在流转的反馈——列表与
     * 统计卡都按这个口径，才不至于「卡片说 12 条、点进去 15 行」。要看归档必须显式
     * 选中「已归档」。规则收在这里而不是散在各调用点，是为了让列表、计数与统计卡天然对齐。
     */
    public boolean allows(FeedbackStatus candidate) {
        return status == null ? candidate != FeedbackStatus.ARCHIVED : candidate == status;
    }

    public FeedbackType type() {
        return type;
    }

    public FeedbackQuery type(FeedbackType value) {
        this.type = value;
        return this;
    }

    public FeedbackSeverity severity() {
        return severity;
    }

    public FeedbackQuery severity(FeedbackSeverity value) {
        this.severity = value;
        return this;
    }

    public String assigneeId() {
        return assigneeId == null ? "" : assigneeId;
    }

    public FeedbackQuery assigneeId(String value) {
        this.assigneeId = value == null ? "" : value.trim();
        return this;
    }

    public Boolean unassignedOnly() {
        return unassignedOnly;
    }

    public FeedbackQuery unassignedOnly(Boolean value) {
        this.unassignedOnly = value;
        return this;
    }

    public Boolean openOnly() {
        return openOnly;
    }

    public FeedbackQuery openOnly(Boolean value) {
        this.openOnly = value;
        return this;
    }

    public String authorId() {
        return authorId == null ? "" : authorId;
    }

    public FeedbackQuery authorId(String value) {
        this.authorId = value == null ? "" : value.trim();
        return this;
    }

    public Long from() {
        return from;
    }

    public FeedbackQuery from(Long value) {
        this.from = value;
        return this;
    }

    public Long to() {
        return to;
    }

    public FeedbackQuery to(Long value) {
        this.to = value;
        return this;
    }

    public int page() {
        return Math.max(1, page);
    }

    public FeedbackQuery page(int value) {
        this.page = Math.max(1, value);
        return this;
    }

    public int size() {
        return size <= 0 ? 10 : Math.min(100, size);
    }

    public FeedbackQuery size(int value) {
        this.size = Math.max(1, Math.min(100, value));
        return this;
    }
}
