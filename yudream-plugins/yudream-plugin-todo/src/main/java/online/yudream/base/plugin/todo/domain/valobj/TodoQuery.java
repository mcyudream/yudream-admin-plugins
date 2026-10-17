package online.yudream.base.plugin.todo.domain.valobj;

import online.yudream.base.plugin.todo.domain.enumerate.TodoPriority;
import online.yudream.base.plugin.todo.domain.enumerate.TodoStatus;

/**
 * 待办查询条件。
 *
 * <p>{@code ownerId} 仅由管理员用例填充，用于跨用户数据范围；用户用例必须留空并由调用方按归属约束查询。
 */
public class TodoQuery {

    private String keyword;
    private TodoStatus status;
    private TodoPriority priority;
    private String ownerId;
    /** 归属人或参与人视为可见，用于用户端「我的清单」的范围查询。 */
    private String visibleTo;
    private boolean overdueOnly;
    /** 归档过滤：null=不过滤（管理端全量）；true=仅归档；false=排除归档（用户端默认）。 */
    private Boolean archived;
    private int page;
    private int size;

    public TodoQuery() {
        this.keyword = "";
        this.page = 1;
        this.size = 10;
    }

    public String keyword() {
        return keyword;
    }

    public TodoQuery keyword(String keyword) {
        this.keyword = keyword == null ? "" : keyword.trim();
        return this;
    }

    public TodoStatus status() {
        return status;
    }

    public TodoQuery status(TodoStatus status) {
        this.status = status;
        return this;
    }

    public TodoPriority priority() {
        return priority;
    }

    public TodoQuery priority(TodoPriority priority) {
        this.priority = priority;
        return this;
    }

    public String ownerId() {
        return ownerId;
    }

    public TodoQuery ownerId(String ownerId) {
        this.ownerId = ownerId == null || ownerId.isBlank() ? null : ownerId.trim();
        return this;
    }

    public String visibleTo() {
        return visibleTo;
    }

    /** 用户端专用：把范围限定为「归属人 = userId 或参与人包含 userId」。 */
    public TodoQuery visibleTo(String userId) {
        this.visibleTo = userId == null || userId.isBlank() ? null : userId.trim();
        return this;
    }

    public boolean overdueOnly() {
        return overdueOnly;
    }

    public TodoQuery overdueOnly(boolean overdueOnly) {
        this.overdueOnly = overdueOnly;
        return this;
    }

    public Boolean archived() {
        return archived;
    }

    public TodoQuery archived(Boolean archived) {
        this.archived = archived;
        return this;
    }

    public int page() {
        return page;
    }

    public TodoQuery page(int page) {
        this.page = Math.max(1, page);
        return this;
    }

    public int size() {
        return size;
    }

    public TodoQuery size(int size) {
        this.size = Math.max(1, Math.min(100, size));
        return this;
    }

    public boolean hasKeyword() {
        return !keyword.isBlank();
    }
}
