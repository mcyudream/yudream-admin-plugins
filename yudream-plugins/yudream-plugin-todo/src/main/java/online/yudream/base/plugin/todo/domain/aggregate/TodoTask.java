package online.yudream.base.plugin.todo.domain.aggregate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import online.yudream.base.plugin.todo.domain.enumerate.TodoPriority;
import online.yudream.base.plugin.todo.domain.enumerate.TodoSource;
import online.yudream.base.plugin.todo.domain.enumerate.TodoStatus;
import online.yudream.base.plugin.todo.domain.valobj.TodoSubTask;

/**
 * 待办任务聚合。
 *
 * <p>归属只能由创建时的受信任身份确定；{@code assignedBy} 为空表示用户自建，非空表示管理员派发。
 * 二级小任务依附于本聚合，随任务一起保存，不单独成为聚合根。
 *
 * <p>{@code visibility} 只影响任务库（「从已有任务添加」）的可见范围：PRIVATE 仅归属人与参与人、
 * DEPT 限所选部门、PUBLIC 全站可见；不改变清单本身的可见范围。历史数据缺字段一律按 PRIVATE 处理。
 */
public class TodoTask {

    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_REMARK_LENGTH = 2000;
    private static final int MAX_SUBTASK_COUNT = 30;
    private static final int MAX_PARTICIPANT_COUNT = 20;

    private final String id;
    private final String ownerId;
    private String title;
    private String remark;
    private TodoPriority priority;
    private TodoStatus status;
    private Long dueAt;
    private Long completedAt;
    private final TodoSource source;
    private final String assignedBy;
    private final long createdAt;
    private long updatedAt;
    private final List<TodoSubTask> subTasks = new ArrayList<>();
    private final List<String> participantIds = new ArrayList<>();
    private String visibility = VISIBILITY_PRIVATE;
    private List<String> deptIds = new ArrayList<>();
    private List<String> deptNames = new ArrayList<>();
    /** 归档标记：仅归属人可操作，归档任务从默认清单、统计与日程中收起。 */
    private boolean archived;

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_DEPT = "DEPT";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    public TodoTask(String id, String ownerId, String title, String remark, TodoPriority priority, TodoStatus status,
                    Long dueAt, Long completedAt, TodoSource source, String assignedBy, long createdAt, long updatedAt,
                    List<TodoSubTask> subTasks, List<String> participantIds) {
        this.id = id;
        this.ownerId = ownerId;
        this.title = normalizeTitle(title);
        this.remark = normalizeRemark(remark);
        this.priority = priority == null ? TodoPriority.NORMAL : priority;
        this.status = status == null ? TodoStatus.PENDING : status;
        this.dueAt = dueAt;
        this.completedAt = completedAt;
        this.source = source == null ? TodoSource.SELF : source;
        this.assignedBy = assignedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        if (subTasks != null) {
            this.subTasks.addAll(subTasks);
        }
        this.participantIds.addAll(cleanParticipantIds(participantIds));
    }

    public static TodoTask createSelf(String id, String ownerId, String title, String remark,
                                      TodoPriority priority, Long dueAt, long now) {
        return createSelf(id, ownerId, title, remark, priority, dueAt, List.of(), now);
    }

    public static TodoTask createSelf(String id, String ownerId, String title, String remark,
                                      TodoPriority priority, Long dueAt, List<TodoSubTask> subTasks, long now) {
        return createSelf(id, ownerId, title, remark, priority, dueAt, subTasks, List.of(), now);
    }

    public static TodoTask createSelf(String id, String ownerId, String title, String remark,
                                      TodoPriority priority, Long dueAt, List<TodoSubTask> subTasks,
                                      List<String> participantIds, long now) {
        return new TodoTask(id, ownerId, title, remark, priority, TodoStatus.PENDING, dueAt, null,
                TodoSource.SELF, null, now, now, subTasks, participantIds);
    }

    public static TodoTask createAssigned(String id, String ownerId, String title, String remark,
                                          TodoPriority priority, Long dueAt, String assignedBy, long now) {
        return createAssigned(id, ownerId, title, remark, priority, dueAt, assignedBy, List.of(), now);
    }

    public static TodoTask createAssigned(String id, String ownerId, String title, String remark,
                                          TodoPriority priority, Long dueAt, String assignedBy,
                                          List<TodoSubTask> subTasks, long now) {
        return createAssigned(id, ownerId, title, remark, priority, dueAt, assignedBy, subTasks, List.of(), now);
    }

    public static TodoTask createAssigned(String id, String ownerId, String title, String remark,
                                          TodoPriority priority, Long dueAt, String assignedBy,
                                          List<TodoSubTask> subTasks, List<String> participantIds, long now) {
        return new TodoTask(id, ownerId, title, remark, priority, TodoStatus.PENDING, dueAt, null,
                TodoSource.ADMIN, assignedBy, now, now, subTasks, participantIds);
    }

    public void rename(String title, String remark, TodoPriority priority, Long dueAt, long now) {
        this.title = normalizeTitle(title);
        this.remark = normalizeRemark(remark);
        this.priority = priority == null ? TodoPriority.NORMAL : priority;
        this.dueAt = dueAt;
        this.updatedAt = now;
    }

    public void markDone(long now) {
        if (this.status == TodoStatus.DONE) {
            return;
        }
        this.status = TodoStatus.DONE;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void markPending(long now) {
        if (this.status == TodoStatus.PENDING) {
            return;
        }
        this.status = TodoStatus.PENDING;
        this.completedAt = null;
        this.updatedAt = now;
    }

    /**
     * 整体替换二级小任务。
     *
     * <p>由应用层按标题复用已有小任务（保留勾选状态）并生成新 ID，领域只负责上限校验与落地。
     */
    public void replaceSubTasks(List<TodoSubTask> next, long now) {
        List<TodoSubTask> safe = next == null ? List.of() : next;
        if (safe.size() > MAX_SUBTASK_COUNT) {
            throw new IllegalArgumentException("单个待办最多只能有 " + MAX_SUBTASK_COUNT + " 条小任务");
        }
        this.subTasks.clear();
        this.subTasks.addAll(safe);
        this.updatedAt = now;
    }

    /**
     * 整体替换参与人。
     *
     * <p>归属人天然可见本任务，因此不进入参与人列表：重复传入会被忽略，避免同一人既归属又参与。
     */
    public void changeParticipants(List<String> participants, long now) {
        List<String> next = cleanParticipantIds(participants);
        if (next.size() > MAX_PARTICIPANT_COUNT) {
            throw new IllegalArgumentException("单个待办最多只能有 " + MAX_PARTICIPANT_COUNT + " 位参与人");
        }
        this.participantIds.clear();
        this.participantIds.addAll(next);
        this.updatedAt = now;
    }

    /**
     * 追加参与人：用于「派发时复用已有任务 / 用户从任务库加入」，不新建任务副本。
     *
     * <p>归属人天然可见本任务，因此不进入参与人列表；已存在、重复或超上限的会被忽略或拒绝。
     */
    public void addParticipants(List<String> newParticipants, long now) {
        List<String> merged = new ArrayList<>(participantIds);
        Set<String> seen = new HashSet<>(participantIds);
        for (String raw : newParticipants) {
            String id = raw == null ? "" : raw.trim();
            if (id.isEmpty() || id.equals(ownerId) || seen.contains(id)) {
                continue;
            }
            if (merged.size() >= MAX_PARTICIPANT_COUNT) {
                throw new IllegalArgumentException("单个待办最多只能有 " + MAX_PARTICIPANT_COUNT + " 位参与人");
            }
            merged.add(id);
            seen.add(id);
        }
        this.participantIds.clear();
        this.participantIds.addAll(merged);
        this.updatedAt = now;
    }

    /** 勾选或取消单条小任务；小任务不存在时抛错，避免前端静默失败。 */
    public void toggleSubTask(String subTaskId, boolean done, long now) {
        subTask(subTaskId).markDone(done, now);
        this.updatedAt = now;
    }

    /** 当前用户加入小任务负责人：小任务不存在时报错，重复加入忽略。 */
    public void joinSubTask(String subTaskId, String userId, long now) {
        subTask(subTaskId).addAssignee(userId, now);
        this.updatedAt = now;
    }

    /** 当前用户退出小任务负责人：小任务不存在时报错，未加入时忽略。 */
    public void leaveSubTask(String subTaskId, String userId, long now) {
        subTask(subTaskId).removeAssignee(userId, now);
        this.updatedAt = now;
    }

    private TodoSubTask subTask(String subTaskId) {
        return subTasks.stream()
                .filter(item -> item.id().equals(subTaskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("小任务不存在或已被删除"));
    }

    /** 未完成且已超过截止时间即视为逾期。 */
    public boolean overdue(long now) {
        return status == TodoStatus.PENDING && dueAt != null && dueAt > 0L && dueAt < now;
    }

    /** 截止时间落在「今天」内。 */
    public boolean dueToday(long dayStart, long dayEnd) {
        return dueAt != null && dueAt > 0L && dueAt >= dayStart && dueAt < dayEnd;
    }

    public String id() {
        return id;
    }

    public String ownerId() {
        return ownerId;
    }

    public String title() {
        return title;
    }

    public String remark() {
        return remark;
    }

    public TodoPriority priority() {
        return priority;
    }

    public TodoStatus status() {
        return status;
    }

    public Long dueAt() {
        return dueAt;
    }

    public Long completedAt() {
        return completedAt;
    }

    public TodoSource source() {
        return source;
    }

    public String assignedBy() {
        return assignedBy;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public List<TodoSubTask> subTasks() {
        return List.copyOf(subTasks);
    }

    public List<String> participantIds() {
        return List.copyOf(participantIds);
    }

    /** 参与人（非归属人）也能看到并推进任务，但不能编辑、删除或改参与人。 */
    public boolean isParticipant(String userId) {
        return userId != null && !userId.isBlank() && participantIds.contains(userId);
    }

    /** 创建者：用户自建为归属人，管理员派发为派发人。 */
    public String creatorId() {
        return assignedBy == null || assignedBy.isBlank() ? ownerId : assignedBy;
    }

    /** 归属人或参与人都视为可见；用于用户端「我的清单」的范围判定。 */
    public boolean visibleTo(String userId) {
        return userId != null && (userId.equals(ownerId) || isParticipant(userId));
    }

    public String visibility() {
        return visibility;
    }

    public List<String> deptIds() {
        return List.copyOf(deptIds);
    }

    public List<String> deptNames() {
        return List.copyOf(deptNames);
    }

    /** 写入任务库可见范围；取值与部门校验由服务层完成，这里只做兜底归一。 */
    public boolean archived() {
        return archived;
    }

    /** 仓储重建用：不更新时间戳。 */
    public void assignArchived(boolean value) {
        this.archived = value;
    }

    /** 归档/取消归档；归档资格（须已完成）由应用服务校验。 */
    public void changeArchived(boolean value, long now) {
        this.archived = value;
        this.updatedAt = now;
    }

    public void assignVisibility(String visibility, List<String> deptIds, List<String> deptNames) {
        this.visibility = visibility == null || visibility.isBlank()
                ? VISIBILITY_PRIVATE : visibility;
        this.deptIds.clear();
        if (deptIds != null) {
            this.deptIds.addAll(deptIds);
        }
        this.deptNames.clear();
        if (deptNames != null) {
            this.deptNames.addAll(deptNames);
        }
    }

    /** 判断查看者是否落在任务库可见范围内（部门交集由服务层传入）。 */
    public boolean visibleInLibrary(String viewerId, java.util.Set<String> viewerDeptIds) {
        if (visibleTo(viewerId)) {
            return true;
        }
        if (VISIBILITY_PUBLIC.equals(visibility)) {
            return true;
        }
        if (VISIBILITY_DEPT.equals(visibility) && viewerDeptIds != null) {
            return deptIds.stream().anyMatch(viewerDeptIds::contains);
        }
        return false;
    }

    public int subTaskTotal() {
        return subTasks.size();
    }

    public int subTaskDone() {
        return (int) subTasks.stream().filter(TodoSubTask::done).count();
    }

    /** 清洗参与人 ID：去空白、去重、剔除归属人，并截断到上限。 */
    private List<String> cleanParticipantIds(List<String> raw) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        for (String item : raw) {
            String id = item == null ? "" : item.trim();
            if (id.isEmpty() || id.equals(ownerId)) {
                continue;
            }
            ids.add(id);
            if (ids.size() >= MAX_PARTICIPANT_COUNT) {
                break;
            }
        }
        return new ArrayList<>(ids);
    }

    private static String normalizeTitle(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("待办标题不能为空");
        }
        if (value.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("待办标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }
        return value;
    }

    private static String normalizeRemark(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > MAX_REMARK_LENGTH) {
            throw new IllegalArgumentException("待办备注不能超过 " + MAX_REMARK_LENGTH + " 个字符");
        }
        return value;
    }
}
