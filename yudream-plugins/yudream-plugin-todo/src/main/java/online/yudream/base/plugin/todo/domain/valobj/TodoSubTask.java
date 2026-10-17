package online.yudream.base.plugin.todo.domain.valobj;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 二级小任务。
 *
 * <p>依附于 {@link online.yudream.base.plugin.todo.domain.aggregate.TodoTask} 存在，不单独成为聚合；
 * 勾选状态与小任务自身的完成时间由宿主任务统一维护。{@code assigneeIds} 记录被指派负责该小任务的成员，
 * 便于派发时把人员精确到小任务粒度。{@code priority}/{@code dueAt} 支持小任务单独设置优先级与截止时间，
 * 为空表示跟随宿主任务的整体设置。
 */
public class TodoSubTask {

    private static final int MAX_TITLE_LENGTH = 120;

    private static final int MAX_ASSIGNEE_COUNT = 20;

    private final String id;
    private String title;
    private boolean done;
    private long updatedAt;
    private final List<String> assigneeIds;
    /** 小任务单独设置的优先级；null 表示跟随宿主任务整体设置。 */
    private final String priority;
    /** 小任务单独设置的截止时间（epoch 毫秒）；null 表示跟随宿主任务整体设置。 */
    private final Long dueAt;

    public TodoSubTask(String id, String title, boolean done, long updatedAt, List<String> assigneeIds, String priority, Long dueAt) {
        this.id = id;
        this.title = normalizeTitle(title);
        this.done = done;
        this.updatedAt = updatedAt;
        this.assigneeIds = assigneeIds == null ? new ArrayList<>() : new ArrayList<>(assigneeIds);
        this.priority = normalizePriority(priority);
        this.dueAt = dueAt == null || dueAt <= 0 ? null : dueAt;
    }

    public static TodoSubTask create(String id, String title, long now) {
        return new TodoSubTask(id, title, false, now, List.of(), null, null);
    }

    public void rename(String title, long now) {
        this.title = normalizeTitle(title);
        this.updatedAt = now;
    }

    public void markDone(boolean done, long now) {
        this.done = done;
        this.updatedAt = now;
    }

    /** 把成员加入负责人列表；空 ID 或已在列表时静默忽略。 */
    public void addAssignee(String userId, long now) {
        String id = userId == null ? "" : userId.trim();
        if (id.isEmpty() || assigneeIds.contains(id)) {
            return;
        }
        if (assigneeIds.size() >= MAX_ASSIGNEE_COUNT) {
            throw new IllegalArgumentException("单条小任务最多只能有 " + MAX_ASSIGNEE_COUNT + " 位负责人");
        }
        assigneeIds.add(id);
        updatedAt = now;
    }

    /** 把成员移出负责人列表；不在列表时静默忽略。 */
    public void removeAssignee(String userId, long now) {
        String id = userId == null ? "" : userId.trim();
        if (id.isEmpty() || !assigneeIds.contains(id)) {
            return;
        }
        assigneeIds.remove(id);
        updatedAt = now;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public boolean done() {
        return done;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public List<String> assigneeIds() {
        return new ArrayList<>(assigneeIds);
    }

    public String priority() {
        return priority;
    }

    public Long dueAt() {
        return dueAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TodoSubTask that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    private static String normalizePriority(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.isEmpty() ? null : value;
    }

    private static String normalizeTitle(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("小任务标题不能为空");
        }
        if (value.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("小任务标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }
        return value;
    }
}
