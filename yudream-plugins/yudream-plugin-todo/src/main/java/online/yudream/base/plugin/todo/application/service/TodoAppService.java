package online.yudream.base.plugin.todo.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import online.yudream.base.plugin.spi.system.user.PluginUserOption;
import online.yudream.base.plugin.spi.system.user.PluginUserProfile;
import online.yudream.base.plugin.spi.system.user.PluginUserService;
import online.yudream.base.plugin.todo.application.TodoNotFoundException;
import online.yudream.base.plugin.todo.application.assembler.TodoTaskAssembler;
import online.yudream.base.plugin.todo.application.cmd.TodoTaskSaveCmd;
import online.yudream.base.plugin.todo.application.dto.TodoAdminStatsDTO;
import online.yudream.base.plugin.todo.application.dto.TodoOverviewDTO;
import online.yudream.base.plugin.todo.application.dto.TodoPageDTO;
import online.yudream.base.plugin.todo.application.dto.TodoScheduleDTO;
import online.yudream.base.plugin.todo.application.dto.TodoScheduleDayDTO;
import online.yudream.base.plugin.todo.application.dto.TodoTaskDTO;
import java.util.Locale;
import online.yudream.base.plugin.spi.system.user.PluginDeptOption;
import online.yudream.base.plugin.spi.system.user.PluginUserDept;
import online.yudream.base.plugin.todo.application.dto.TodoDeptOptionDTO;
import online.yudream.base.plugin.todo.application.dto.TodoTemplateDTO;import online.yudream.base.plugin.todo.application.dto.TodoUserOptionDTO;
import online.yudream.base.plugin.todo.domain.aggregate.TodoTask;
import online.yudream.base.plugin.todo.domain.enumerate.TodoPriority;
import online.yudream.base.plugin.todo.domain.enumerate.TodoSource;
import online.yudream.base.plugin.todo.domain.enumerate.TodoStatus;
import online.yudream.base.plugin.todo.domain.repo.TodoTaskRepository;
import online.yudream.base.plugin.todo.domain.valobj.TodoQuery;
import online.yudream.base.plugin.todo.domain.valobj.TodoSubTask;
import online.yudream.base.plugin.todo.infrastructure.Ids;
import online.yudream.base.plugin.todo.infrastructure.TimeSupport;
import online.yudream.base.plugin.todo.interfaces.request.TodoSubTaskAssigneeItem;
import online.yudream.base.plugin.todo.interfaces.request.TodoSubTaskItem;

/**
 * 待办用例编排。
 *
 * <p>用户用例与管理用例严格分离：用户用例只接受受信任的归属 ID，管理用例才接受跨用户目标 ID。
 * 用户端复用「任务库」时不接受模板归属参数，避免通过模板 ID 越权读取他人私人任务内容。
 */
public class TodoAppService {

    private static final int OVERVIEW_UPCOMING_LIMIT = 50;
    private static final int OVERVIEW_SCAN_LIMIT = 500;
    private static final int TEMPLATE_LIMIT = 100;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int MAX_SCHEDULE_DAYS = 92;

    private final TodoTaskRepository repository;
    private final PluginUserService users;
    private final TodoTaskAssembler assembler = new TodoTaskAssembler();

    public TodoAppService(TodoTaskRepository repository, PluginUserService users) {
        this.repository = repository;
        this.users = users;
    }

    // ------------------------------------------------------------------ 用户端

    /** 个人清单包含「我创建的 / 派发给我的」以及我作为参与人协作的任务。 */
    public TodoPageDTO<TodoTaskDTO> listMyTasks(String ownerId, TodoQuery query) {
        TodoQuery scoped = query.visibleTo(ownerId);
        List<TodoTaskDTO> records = repository.findByQuery(scoped).stream()
                .map(task -> dto(task, ownerId, false))
                .toList();
        return new TodoPageDTO<>(records, repository.countByQuery(scoped), scoped.page(), scoped.size());
    }

    public TodoOverviewDTO myOverview(String ownerId) {
        long now = System.currentTimeMillis();
        long dayStart = TimeSupport.dayStart(now);
        long dayEnd = TimeSupport.dayEnd(now);
        List<TodoTask> tasks = repository.findAllVisibleTo(ownerId, OVERVIEW_SCAN_LIMIT);
        long total = tasks.size();
        long done = tasks.stream().filter(task -> task.status() == TodoStatus.DONE).count();
        long overdue = tasks.stream().filter(task -> task.overdue(now)).count();
        long archived = repository.countVisibleArchived(ownerId);
        long dueToday = tasks.stream().filter(task -> task.status() != TodoStatus.DONE
                && task.dueToday(dayStart, dayEnd)).count();
        List<TodoTaskDTO> upcoming = tasks.stream()
                .filter(task -> task.status() == TodoStatus.PENDING)
                .sorted(TodoAppService::byUrgency)
                .limit(OVERVIEW_UPCOMING_LIMIT)
                .map(task -> dto(task, ownerId, false))
                .toList();
        Map<String, Long> byPriority = emptyPriorityBuckets();
        for (TodoTask task : tasks) {
            if (task.status() != TodoStatus.DONE) {
                byPriority.merge(task.priority().code(), 1L, Long::sum);
            }
        }
        int completionRate = total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        return new TodoOverviewDTO(total, total - done, done, overdue, archived, dueToday, completionRate, upcoming, byPriority);
    }

    /** 固定输出全部优先级，避免前端出现缺失档位导致条形图错位。 */
    private static Map<String, Long> emptyPriorityBuckets() {
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (TodoPriority priority : TodoPriority.values()) {
            buckets.put(priority.code(), 0L);
        }
        return buckets;
    }

    public TodoTaskDTO createMyTask(String ownerId, TodoTaskSaveCmd cmd) {
        long now = System.currentTimeMillis();
        List<String> participants = withSubTaskAssignees(cmd.safeSubTasks(), cmd.safeParticipantIds());
        TodoTask task = TodoTask.createSelf(
                Ids.next(),
                ownerId,
                cmd.title(),
                cmd.remark(),
                TodoPriority.of(cmd.priority()),
                cmd.dueAt(),
                buildSubTasks(cmd.safeSubTasks(), List.of(), now),
                participants,
                now
        );
        VisibilityAssignment visibility = resolveVisibilityForUser(ownerId, cmd.visibility(), cmd.safeDeptIds());
        task.assignVisibility(visibility.value(), visibility.deptIds(), visibility.deptNames());
        return dto(repository.save(task), ownerId, false);
    }

    /** 编辑：参与人只有创建者本人能改；派发给我的任务由管理员维护参与人。 */
    public TodoTaskDTO updateMyTask(String taskId, String ownerId, TodoTaskSaveCmd cmd) {
        TodoTask task = requireOwned(taskId, ownerId);
        long now = System.currentTimeMillis();
        task.rename(cmd.title(), cmd.remark(), TodoPriority.of(cmd.priority()), cmd.dueAt(), now);
        if (ownerId.equals(task.creatorId())) {
            List<String> participants = withSubTaskAssignees(cmd.safeSubTasks(), cmd.safeParticipantIds());
            task.replaceSubTasks(buildSubTasks(cmd.safeSubTasks(), task.subTasks(), now), now);
            task.changeParticipants(participants, now);
            VisibilityAssignment visibility = resolveVisibilityForUser(ownerId, cmd.visibility(), cmd.safeDeptIds());
            task.assignVisibility(visibility.value(), visibility.deptIds(), visibility.deptNames());
        }
        else {
            // 非创建者不能改参与人；小任务按提交内容更新，负责人保留前端逐条指派的值。
            task.replaceSubTasks(buildSubTasks(cmd.safeSubTasks(), task.subTasks(), now), now);
        }
        return dto(repository.save(task), ownerId, false);
    }

    /**
     * 归档/取消归档：仅归属人可操作；归档要求任务已完成。
     * 归档任务从默认清单、统计与日程中收起，仅在「已归档」筛选下可见。
     */
    public TodoTaskDTO setMyTaskArchived(String taskId, String ownerId, boolean archived) {
        TodoTask task = requireOwned(taskId, ownerId);
        if (archived && task.status() != TodoStatus.DONE) {
            throw new IllegalArgumentException("只有已完成的任务才能归档");
        }
        task.changeArchived(archived, System.currentTimeMillis());
        return dto(repository.save(task), ownerId, false);
    }

    /** 从任务库加入的任务退出协作：把自己从参与人中移除；归属人不可退出，只能删除或归档任务。 */
    public TodoTaskDTO leaveTask(String taskId, String userId) {
        TodoTask task = repository.findById(taskId)
                .orElseThrow(() -> new TodoNotFoundException("待办不存在或已被删除"));
        if (task.ownerId().equals(userId)) {
            throw new IllegalArgumentException("归属人不能退出，可删除或归档该任务");
        }
        if (!task.isParticipant(userId)) {
            throw new IllegalArgumentException("您不是该任务的参与人，无需退出");
        }
        List<String> remaining = task.participantIds().stream()
                .filter(id -> !id.equals(userId))
                .toList();
        task.changeParticipants(remaining, System.currentTimeMillis());
        return dto(repository.save(task), userId, false);
    }

    /** 完成状态对参与人开放：协作任务需要能被参与人推进。勾选整体完成时，下属小任务一并标记为完成。 */
    public TodoTaskDTO setMyTaskDone(String taskId, String ownerId, boolean done) {
        TodoTask task = requireVisible(taskId, ownerId);
        long now = System.currentTimeMillis();
        if (done) {
            for (TodoSubTask sub : task.subTasks()) {
                sub.markDone(true, now);
            }
            task.markDone(now);
        }
        else {
            task.markPending(now);
        }
        return dto(repository.save(task), ownerId, false);
    }

    /** 勾选或取消单条二级小任务；主任务状态不联动，避免误判整条待办已完成。 */
    public TodoTaskDTO toggleMySubTask(String taskId, String subTaskId, String ownerId, boolean done) {
        TodoTask task = requireVisible(taskId, ownerId);
        task.toggleSubTask(subTaskId, done, System.currentTimeMillis());
        return dto(repository.save(task), ownerId, false);
    }

    /** 加入小任务：把自己加入该小任务的负责人列表；归属人与参与人都可领取。 */
    public TodoTaskDTO joinMySubTask(String taskId, String subTaskId, String userId) {
        TodoTask task = requireVisible(taskId, userId);
        task.joinSubTask(subTaskId, userId, System.currentTimeMillis());
        return dto(repository.save(task), userId, false);
    }

    /** 退出小任务：把自己从该小任务的负责人列表移除；未加入时静默忽略。 */
    public TodoTaskDTO leaveMySubTask(String taskId, String subTaskId, String userId) {
        TodoTask task = requireVisible(taskId, userId);
        task.leaveSubTask(subTaskId, userId, System.currentTimeMillis());
        return dto(repository.save(task), userId, false);
    }

    public void deleteMyTask(String taskId, String ownerId) {
        repository.delete(requireOwned(taskId, ownerId));
    }

    public TodoTaskDTO findMyTask(String taskId, String ownerId) {
        return dto(requireVisible(taskId, ownerId), ownerId, false);
    }

    /**
     * 任务库：管理员派发的任务 + 创建者开放了可见范围的任务，按标题去重。
     *
     * <p>可见范围 PRIVATE 的他人任务不进库；DEPT 限查看者所在部门、PUBLIC 全站可见。清单中已有
     * 同名未完成任务的条目会标记 {@code inMyList}，前端置灰且后端再次拦截，避免重复加入个人清单。
     */
    public List<TodoTemplateDTO> myTemplates(String ownerId, String keyword) {
        Set<String> mine = pendingTitles(ownerId);
        return distinctByTitle(library(ownerId), keyword).stream()
                .map(task -> assembler.toTemplate(task, mine.contains(normalizeTitle(task.title())), this::ownerName))
                .toList();
    }

    /**
     * 从任务库添加一条待办（小任务一并复制，且全部重置为未完成）。
     *
     * <p>归属强制取当前登录者；准入与任务库一致（派发任务或创建者开放了可见范围的任务），
     * 避免通过模板 ID 复制他人私人任务。清单中已有同名未完成任务时直接拒绝。
     */
    public TodoTaskDTO createFromTemplate(String ownerId, String templateId, Long dueAt) {
        TodoTask template = repository.findById(templateId)
                .orElseThrow(() -> new TodoNotFoundException("所选任务不存在或已被删除"));
        if (!inLibraryScope(template, ownerId)) {
            throw new IllegalArgumentException("该任务未开放可见范围，无法添加");
        }
        if (pendingTitles(ownerId).contains(normalizeTitle(template.title()))) {
            throw new IllegalArgumentException("你的清单中已有同名待办，无需重复添加");
        }
        long now = System.currentTimeMillis();
        TodoTask task = TodoTask.createSelf(
                Ids.next(),
                ownerId,
                template.title(),
                template.remark(),
                template.priority(),
                dueAt,
                copySubTasks(template.subTasks(), now),
                List.of(),
                now
        );
        return dto(repository.save(task), ownerId, false);
    }

    /** 按自然日铺开的日程表；区间上限 {@link #MAX_SCHEDULE_DAYS} 天，避免一次拉取过大范围。 */
    public TodoScheduleDTO mySchedule(String ownerId, long from, long to) {
        long start = TimeSupport.dayStart(from);
        long end = TimeSupport.dayStart(to);
        if (end < start) {
            long swap = start;
            start = end;
            end = swap;
        }
        if ((end - start) / DAY_MILLIS + 1 > MAX_SCHEDULE_DAYS) {
            end = TimeSupport.dayStartPlusDays(start, MAX_SCHEDULE_DAYS - 1);
        }
        long now = System.currentTimeMillis();
        Map<String, List<TodoTask>> grouped = new LinkedHashMap<>();
        Map<String, Integer> subDueByDay = new LinkedHashMap<>();
        for (TodoTask task : repository.findAllVisibleTo(ownerId, OVERVIEW_SCAN_LIMIT)) {
            // 小任务截止按日计数：单独设过截止的落在自己的日期，未设置的跟随宿主任务截止日。
            for (TodoSubTask sub : task.subTasks()) {
                Long subDue = sub.dueAt() != null ? sub.dueAt() : task.dueAt();
                if (subDue == null || subDue <= 0L) {
                    continue;
                }
                long subDay = TimeSupport.dayStart(subDue);
                if (subDay < start || subDay > end) {
                    continue;
                }
                subDueByDay.merge(TimeSupport.formatDate(subDay), 1, Integer::sum);
            }
            if (task.dueAt() == null || task.dueAt() <= 0L) {
                continue;
            }
            long day = TimeSupport.dayStart(task.dueAt());
            if (day < start || day > end) {
                continue;
            }
            grouped.computeIfAbsent(TimeSupport.formatDate(day), key -> new ArrayList<>()).add(task);
        }
        List<TodoScheduleDayDTO> days = new ArrayList<>();
        for (long day = start; day <= end; day += DAY_MILLIS) {
            String key = TimeSupport.formatDate(day);
            List<TodoTask> matched = grouped.getOrDefault(key, List.of()).stream()
                    .sorted(TodoAppService::byUrgency)
                    .toList();
            List<TodoTaskDTO> items = matched.stream()
                    .map(task -> dto(task, ownerId, false))
                    .toList();
            int done = (int) matched.stream().filter(task -> task.status() == TodoStatus.DONE).count();
            int overdue = (int) matched.stream().filter(task -> task.overdue(now)).count();
            days.add(new TodoScheduleDayDTO(key, items.size(), done, overdue, subDueByDay.getOrDefault(key, 0), items));
        }
        return new TodoScheduleDTO(TimeSupport.formatDate(start), TimeSupport.formatDate(end), days);
    }

    // ------------------------------------------------------------------ 管理端

    public TodoPageDTO<TodoTaskDTO> adminPageTasks(TodoQuery query) {
        List<TodoTaskDTO> records = repository.findByQuery(query).stream()
                .map(task -> dto(task, null, true))
                .toList();
        return new TodoPageDTO<>(records, repository.countByQuery(query), query.page(), query.size());
    }

    public TodoTaskDTO adminTask(String taskId) {
        return repository.findById(taskId)
                .map(task -> dto(task, null, true))
                .orElseThrow(() -> new TodoNotFoundException("待办不存在或已被删除"));
    }

    /** 可作为派发模板的全部任务（跨用户），按标题去重。 */
    public List<TodoTemplateDTO> adminTemplates(String keyword) {
        return distinctByTitle(scanAll(), keyword).stream()
                .map(task -> assembler.toTemplate(task, false, this::ownerName))
                .toList();
    }

    /**
     * 把指定用户加入某条已有任务的参与人：管理员派发时复用已有任务、或用户从任务库加入都走这里，
     * 不再为每个人复制一份互不关联的副本。归属人天然可见，不重复加入。
     */
    public TodoTaskDTO addParticipants(String taskId, String operatorId, List<String> targetIds, boolean admin) {
        TodoTask task = repository.findById(taskId)
                .orElseThrow(() -> new TodoNotFoundException("待办不存在或已被删除"));
        if (task.status() == TodoStatus.DONE) {
            throw new IllegalArgumentException("已完成的任务不可再添加参与人");
        }
        List<String> toAdd = new ArrayList<>();
        Set<String> existing = new HashSet<>(task.participantIds());
        for (String id : distinct(targetIds)) {
            if (id == null || id.isBlank() || id.equals(task.ownerId()) || existing.contains(id)) {
                continue;
            }
            toAdd.add(id);
        }
        if (toAdd.isEmpty()) {
            throw new IllegalArgumentException("所选用户已是该任务的参与人或归属人");
        }
        task.addParticipants(toAdd, System.currentTimeMillis());
        return dto(repository.save(task), operatorId, admin);
    }

    /** 用户从任务库加入一条已有任务：把当前登录者加入参与人，不新建副本。 */
    public TodoTaskDTO joinTask(String taskId, String userId) {
        TodoTask task = repository.findById(taskId)
                .orElseThrow(() -> new TodoNotFoundException("待办不存在或已被删除"));
        if (!inLibraryScope(task, userId)) {
            throw new IllegalArgumentException("该任务未开放可见范围，无法加入");
        }
        return addParticipants(taskId, userId, List.of(userId), false);
    }

    public TodoTaskDTO adminUpdateTask(String taskId, TodoTaskSaveCmd cmd) {
        TodoTask task = repository.findById(taskId)
                .orElseThrow(() -> new TodoNotFoundException("待办不存在或已被删除"));
        long now = System.currentTimeMillis();
        task.rename(cmd.title(), cmd.remark(), TodoPriority.of(cmd.priority()), cmd.dueAt(), now);
        task.replaceSubTasks(buildSubTasks(cmd.safeSubTasks(), task.subTasks(), now), now);
        task.changeParticipants(cmd.safeParticipantIds(), now);
        VisibilityAssignment visibility = resolveVisibilityForAdmin(cmd.visibility(), cmd.safeDeptIds());
        task.assignVisibility(visibility.value(), visibility.deptIds(), visibility.deptNames());
        return dto(repository.save(task), null, true);
    }

    public void adminDeleteTask(String taskId) {
        TodoTask task = repository.findById(taskId)
                .orElseThrow(() -> new TodoNotFoundException("待办不存在或已被删除"));
        repository.delete(task);
    }

    /**
     * 派发已有任务时按小任务 id 更新负责人：标题与勾选状态原样保留，仅覆盖负责人；
     * 新负责人并入任务参与人，保证被指派的人能看见并推进这条待办。
     */
    public TodoTaskDTO updateSubTaskAssignees(String taskId, List<TodoSubTaskAssigneeItem> rawItems, String operatorId) {
        TodoTask task = repository.findById(taskId)
                .orElseThrow(() -> new TodoNotFoundException("待办不存在或已被删除"));
        Map<String, List<String>> byId = new LinkedHashMap<>();
        for (TodoSubTaskAssigneeItem item : rawItems) {
            if (item != null && !item.safeId().isBlank()) {
                byId.put(item.safeId(), item.safeAssigneeIds());
            }
        }
        if (byId.isEmpty()) {
            return dto(task, operatorId, true);
        }
        long now = System.currentTimeMillis();
        List<TodoSubTask> next = new ArrayList<>();
        for (TodoSubTask sub : task.subTasks()) {
            List<String> assignees = byId.getOrDefault(sub.id(), sub.assigneeIds());
            // 负责人确实发生变化时才刷新小任务的更新时间，未变的子项保持原值。
            long subUpdatedAt = assignees.equals(sub.assigneeIds()) ? sub.updatedAt() : now;
            next.add(new TodoSubTask(sub.id(), sub.title(), sub.done(), subUpdatedAt, assignees, sub.priority(), sub.dueAt()));
        }
        task.replaceSubTasks(next, now);
        Set<String> participantIds = new LinkedHashSet<>(task.participantIds());
        for (TodoSubTask sub : next) {
            for (String assignee : sub.assigneeIds()) {
                if (assignee != null && !assignee.isBlank()) {
                    participantIds.add(assignee.trim());
                }
            }
        }
        task.changeParticipants(new ArrayList<>(participantIds), now);
        return dto(repository.save(task), operatorId, true);
    }

    public TodoAdminStatsDTO adminStats() {
        long now = System.currentTimeMillis();
        List<TodoTask> tasks = scanAll();
        long archived = tasks.stream().filter(TodoTask::archived).count();
        // 已归档任务只计入归档数，不进入 总数/待办/已完成/逾期，与清单页口径一致。
        List<TodoTask> active = tasks.stream().filter(task -> !task.archived()).toList();
        long done = active.stream().filter(task -> task.status() == TodoStatus.DONE).count();
        long overdue = active.stream().filter(task -> task.overdue(now)).count();
        return new TodoAdminStatsDTO(active.size(), active.size() - done, done, overdue, archived);
    }

    public List<TodoUserOptionDTO> searchUsers(String keyword, int page, int size) {
        if (users == null) {
            return List.of();
        }
        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return users.searchUsers(trimmed, null, page, size).stream()
                .map(this::toUserOption)
                .toList();
    }

    public List<TodoUserOptionDTO> usersByIds(List<String> userIds) {
        if (users == null || userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<TodoUserOptionDTO> result = new ArrayList<>();
        for (String userId : distinct(userIds)) {
            Long id = parseLongOrNull(userId);
            if (id == null) {
                continue;
            }
            users.findById(id).map(this::toUserOption).ifPresent(result::add);
        }
        return result;
    }

    // ------------------------------------------------------------------ 内部

    private TodoTaskDTO dto(TodoTask task, String viewerId, boolean viewerAdmin) {
        return assembler.toDTO(task, this::ownerName, viewerId, viewerAdmin);
    }

    /** 任务库候选：派发任务 + 创建者开放可见范围的任务。 */
    private List<TodoTask> library(String viewerId) {
        Set<String> viewerDepts = deptIdsOf(viewerId);
        return scanAll().stream()
                .filter(task -> inLibraryScope(task, viewerId, viewerDepts))
                .toList();
    }

    /** 任务库准入谓词：派发任务全站可复用；自建任务看创建者设置的可见范围。 */
    private boolean inLibraryScope(TodoTask task, String viewerId) {
        return inLibraryScope(task, viewerId, deptIdsOf(viewerId));
    }

    private boolean inLibraryScope(TodoTask task, String viewerId, Set<String> viewerDepts) {
        return task.source() == TodoSource.ADMIN || task.visibleInLibrary(viewerId, viewerDepts);
    }

    // ------------------------------------------------------------------ 可见范围

    public record VisibilityAssignment(String value, List<String> deptIds, List<String> deptNames) {
    }

    /** 当前用户加入的部门选项（用户端可见范围选择器数据源）；SPI 异常时按无部门处理。 */
    public List<TodoDeptOptionDTO> myDepartments(String userId) {
        try {
            List<PluginUserDept> depts = users.listDepartments(Long.parseLong(userId));
            if (depts == null) {
                return List.of();
            }
            return depts.stream()
                    .filter(dept -> dept != null && dept.id() != null)
                    .map(dept -> {
                        String name = dept.name() == null || dept.name().isBlank()
                                ? String.valueOf(dept.id()) : dept.name();
                        return new TodoDeptOptionDTO(String.valueOf(dept.id()), name, name);
                    })
                    .toList();
        }
        catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 全量部门树拍平选项（管理端可见范围选择器数据源）；SPI 异常时按空树处理。 */
    public List<TodoDeptOptionDTO> departmentOptions(String keyword) {
        List<TodoDeptOptionDTO> result = new ArrayList<>();
        try {
            List<PluginDeptOption> roots = users.listDepartments(keyword == null || keyword.isBlank() ? null : keyword.trim());
            for (PluginDeptOption root : roots == null ? List.<PluginDeptOption>of() : roots) {
                flattenDept(root, "", result);
            }
        }
        catch (RuntimeException e) {
            return List.of();
        }
        return result;
    }

    private void flattenDept(PluginDeptOption node, String parentLabel, List<TodoDeptOptionDTO> output) {
        if (node == null || node.id() == null || node.id().isBlank()) {
            return;
        }
        String name = node.name() == null || node.name().isBlank() ? node.id() : node.name();
        String label = parentLabel.isBlank() ? name : parentLabel + " / " + name;
        output.add(new TodoDeptOptionDTO(String.valueOf(node.id()), name, label));
        for (PluginDeptOption child : node.children() == null ? List.<PluginDeptOption>of() : node.children()) {
            flattenDept(child, label, output);
        }
    }

    /** 用户端可见性解析：DEPT 必须显式给出可见部门，且全部属于操作者自己所在的部门。 */
    private VisibilityAssignment resolveVisibilityForUser(String userId, String raw, List<String> deptIds) {
        String normalized = normalizeVisibility(raw);
        if (!TodoTask.VISIBILITY_DEPT.equals(normalized)) {
            return new VisibilityAssignment(normalized, List.of(), List.of());
        }
        List<String> requested = normalizeDeptIds(deptIds);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("可见范围为仅部门时请选择可见部门");
        }
        Map<String, String> own = new HashMap<>();
        for (TodoDeptOptionDTO dept : myDepartments(userId)) {
            own.put(dept.id(), dept.name());
        }
        List<String> names = new ArrayList<>();
        for (String id : requested) {
            String name = own.get(id);
            if (name == null) {
                throw new IllegalArgumentException("只能选择您所在的部门作为可见范围");
            }
            names.add(name);
        }
        return new VisibilityAssignment(normalized, requested, names);
    }

    /** 管理端可见性解析：DEPT 必须显式给出可见部门，且全部存在于全量部门树。 */
    private VisibilityAssignment resolveVisibilityForAdmin(String raw, List<String> deptIds) {
        String normalized = normalizeVisibility(raw);
        if (!TodoTask.VISIBILITY_DEPT.equals(normalized)) {
            return new VisibilityAssignment(normalized, List.of(), List.of());
        }
        List<String> requested = normalizeDeptIds(deptIds);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("可见范围为仅部门时请选择可见部门");
        }
        Set<String> known = new HashSet<>();
        for (TodoDeptOptionDTO dept : departmentOptions(null)) {
            known.add(dept.id());
        }
        for (String id : requested) {
            if (!known.contains(id)) {
                throw new IllegalArgumentException("所选部门不存在，请重新选择");
            }
        }
        return new VisibilityAssignment(normalized, requested, List.of());
    }

    private static String normalizeVisibility(String raw) {
        String normalized = raw == null || raw.isBlank() ? TodoTask.VISIBILITY_PRIVATE : raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case TodoTask.VISIBILITY_PRIVATE, TodoTask.VISIBILITY_DEPT, TodoTask.VISIBILITY_PUBLIC -> normalized;
            default -> throw new IllegalArgumentException("可见范围仅支持 PRIVATE / DEPT / PUBLIC");
        };
    }

    private static List<String> normalizeDeptIds(List<String> deptIds) {
        if (deptIds == null) {
            return List.of();
        }
        return deptIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /** 查看者当前部门 ID 集合；SPI 异常或用户不存在时按无部门处理（不会放大可见范围）。 */
    private Set<String> deptIdsOf(String userId) {
        try {
            List<PluginUserDept> depts = users.listDepartments(Long.parseLong(userId));
            if (depts == null) {
                return Set.of();
            }
            Set<String> ids = new HashSet<>();
            for (PluginUserDept dept : depts) {
                if (dept != null && dept.id() != null) {
                    ids.add(String.valueOf(dept.id()));
                }
            }
            return ids;
        }
        catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** 当前用户清单中未完成任务的标题集合（含协作任务），用于「已存在」判定。 */
    private Set<String> pendingTitles(String userId) {
        Set<String> titles = new HashSet<>();
        for (TodoTask task : repository.findAllVisibleTo(userId, OVERVIEW_SCAN_LIMIT)) {
            if (task.status() != TodoStatus.DONE) {
                titles.add(normalizeTitle(task.title()));
            }
        }
        return titles;
    }

    /** 清单中已有同名未完成任务的归属人集合，用于派发去重。 */
    private Set<String> ownerIdsWithPendingTitle(String title) {
        String needle = normalizeTitle(title);
        Set<String> owners = new HashSet<>();
        for (TodoTask task : scanAll()) {
            if (task.status() != TodoStatus.DONE && needle.equals(normalizeTitle(task.title()))) {
                owners.add(task.ownerId());
            }
        }
        return owners;
    }

    /**
     * 按标题同步小任务列表：标题相同则复用原对象以保留勾选状态，新标题才生成新 ID。
     *
     * <p>前端提交的是带负责人的小任务数组，勾选状态由后端持有，避免前端往返时把已完成的小任务冲掉；
     * 复用的小任务保留其原有负责人。
     */
    /** 小任务优先级归一：空值表示跟随任务整体设置；无法识别的值回退为 null。 */
    private String resolvePriority(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return TodoPriority.of(value).code();
        }
        catch (IllegalArgumentException cause) {
            return null;
        }
    }

    private List<TodoSubTask> buildSubTasks(List<TodoSubTaskItem> items, List<TodoSubTask> existing, long now) {
        Map<String, TodoSubTask> byTitle = new LinkedHashMap<>();
        for (TodoSubTask item : existing) {
            byTitle.putIfAbsent(item.title(), item);
        }
        List<TodoSubTask> next = new ArrayList<>();
        if (items != null) {
            for (TodoSubTaskItem raw : items) {
                if (raw == null) {
                    continue;
                }
                String title = raw.safeTitle().trim();
                if (title.isEmpty()) {
                    continue;
                }
                TodoSubTask matched = byTitle.remove(title);
                next.add(matched == null
                        ? new TodoSubTask(Ids.next(), title, false, now, raw.safeAssigneeIds(), resolvePriority(raw.safePriority()), raw.safeDueAt())
                        : new TodoSubTask(matched.id(), matched.title(), matched.done(), now, raw.safeAssigneeIds(), resolvePriority(raw.safePriority()), raw.safeDueAt()));
            }
        }
        return next;
    }

    /**
     * 把小任务负责人并入任务参与人，保证被指派的人能看见并推进这条待办。
     *
     * <p>小任务负责人在 UI 中单独指派；提交的负责人会并入任务参与人，
     * 保证负责人天然可见可推进；未指派的小任务对全体参与人生效。
     */
    private List<String> withSubTaskAssignees(List<TodoSubTaskItem> items, List<String> base) {
        Set<String> ids = new LinkedHashSet<>(base == null ? List.of() : base);
        if (items != null) {
            for (TodoSubTaskItem item : items) {
                for (String assignee : item.safeAssigneeIds()) {
                    if (assignee != null && !assignee.isBlank()) {
                        ids.add(assignee.trim());
                    }
                }
            }
        }
        return new ArrayList<>(ids);
    }


    /** 复制小任务：新 ID 且全部重置为未完成，但保留各小任务的负责人，避免把模板的指派信息丢掉。 */
    private List<TodoSubTask> copySubTasks(List<TodoSubTask> source, long now) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<TodoSubTask> copies = new ArrayList<>();
        for (TodoSubTask item : source) {
            copies.add(new TodoSubTask(Ids.next(), item.title(), false, now, item.assigneeIds(), item.priority(), item.dueAt()));
        }
        return copies;
    }

    private List<TodoTask> distinctByTitle(List<TodoTask> tasks, String keyword) {
        String needle = keyword == null || keyword.isBlank() ? "" : keyword.trim().toLowerCase();
        List<TodoTask> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparingLong(TodoTask::updatedAt).reversed());
        Map<String, TodoTask> byTitle = new LinkedHashMap<>();
        for (TodoTask task : sorted) {
            if (!needle.isEmpty()
                    && !task.title().toLowerCase().contains(needle)
                    && !task.remark().toLowerCase().contains(needle)) {
                continue;
            }
            byTitle.putIfAbsent(task.title(), task);
            if (byTitle.size() >= TEMPLATE_LIMIT) {
                break;
            }
        }
        return new ArrayList<>(byTitle.values());
    }

    private List<TodoTask> scanAll() {
        List<TodoTask> tasks = new ArrayList<>();
        int page = 1;
        while (true) {
            List<TodoTask> batch = repository.findByQuery(new TodoQuery().page(page).size(OVERVIEW_SCAN_LIMIT));
            if (batch.isEmpty()) {
                break;
            }
            tasks.addAll(batch);
            if (batch.size() < OVERVIEW_SCAN_LIMIT) {
                break;
            }
            page++;
        }
        return tasks;
    }

    /** 归属人才能编辑或删除：参与人只能推进任务。 */
    private TodoTask requireOwned(String taskId, String ownerId) {
        return repository.findByIdAndOwnerId(taskId, ownerId)
                .orElseThrow(() -> new TodoNotFoundException("待办不存在或已被删除"));
    }

    /** 归属人与参与人都可读写推进类操作，但范围仍受本用户约束。 */
    private TodoTask requireVisible(String taskId, String userId) {
        return repository.findByIdVisibleTo(taskId, userId)
                .orElseThrow(() -> new TodoNotFoundException("待办不存在或已被删除"));
    }

    private TodoUserOptionDTO toUserOption(PluginUserOption user) {
        return new TodoUserOptionDTO(
                stringOrEmpty(user.id()),
                user.username(),
                user.nickname(),
                user.email(),
                user.avatar(),
                user.status()
        );
    }

    private TodoUserOptionDTO toUserOption(PluginUserProfile user) {
        return new TodoUserOptionDTO(
                String.valueOf(user.id()),
                user.username(),
                user.nickname(),
                user.email(),
                user.avatar(),
                user.status()
        );
    }

    private String ownerName(String ownerId) {
        if (users == null || ownerId == null || ownerId.isBlank()) {
            return "";
        }
        Long id = parseLongOrNull(ownerId);
        if (id == null) {
            return "";
        }
        return users.findById(id)
                .map(user -> user.nickname() == null || user.nickname().isBlank() ? user.username() : user.nickname())
                .orElse("");
    }

    private static String normalizeTitle(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private static int byUrgency(TodoTask left, TodoTask right) {
        int byPriority = Integer.compare(right.priority().weight(), left.priority().weight());
        if (byPriority != 0) {
            return byPriority;
        }
        long leftDue = left.dueAt() == null ? Long.MAX_VALUE : left.dueAt();
        long rightDue = right.dueAt() == null ? Long.MAX_VALUE : right.dueAt();
        return Long.compare(leftDue, rightDue);
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
