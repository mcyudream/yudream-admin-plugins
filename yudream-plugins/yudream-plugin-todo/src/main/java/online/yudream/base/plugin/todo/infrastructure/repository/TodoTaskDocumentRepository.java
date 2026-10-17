package online.yudream.base.plugin.todo.infrastructure.repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.todo.domain.aggregate.TodoTask;
import online.yudream.base.plugin.todo.domain.enumerate.TodoPriority;
import online.yudream.base.plugin.todo.domain.enumerate.TodoSource;
import online.yudream.base.plugin.todo.domain.enumerate.TodoStatus;
import online.yudream.base.plugin.todo.domain.repo.TodoTaskRepository;
import online.yudream.base.plugin.todo.domain.valobj.TodoQuery;
import online.yudream.base.plugin.todo.domain.valobj.TodoSubTask;
import online.yudream.base.plugin.todo.infrastructure.Ids;

/**
 * 待办任务文档仓储。
 *
 * <p>宿主 {@code findByField} 目前存在「先分页后过滤」的回归，因此带筛选的查询统一走全量扫描
 * （每页 200 条）后在内存中过滤、排序与分页，保证 total 与实际命中一致。
 */
public class TodoTaskDocumentRepository implements TodoTaskRepository {

    private static final String COLLECTION = "todo-tasks";
    private static final int SCAN_PAGE_SIZE = 200;
    private static final int MAX_SCAN_PAGES = 500;

    private final PluginDocumentStore documents;

    public TodoTaskDocumentRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    @Override
    public TodoTask save(TodoTask task) {
        return toTask(documents.save(COLLECTION, task.id(), document(task)));
    }

    @Override
    public Optional<TodoTask> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return documents.findById(COLLECTION, id).map(this::toTask);
    }

    @Override
    public Optional<TodoTask> findByIdAndOwnerId(String id, String ownerId) {
        return findById(id).filter(task -> task.ownerId().equals(ownerId));
    }

    @Override
    public Optional<TodoTask> findByIdVisibleTo(String id, String userId) {
        return findById(id).filter(task -> task.visibleTo(userId));
    }

    @Override
    public List<TodoTask> findAllVisibleTo(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        List<TodoTask> matched = scan().stream()
                .filter(task -> task.visibleTo(userId))
                .filter(task -> !task.archived())
                .sorted(TASK_ORDER)
                .toList();
        if (limit <= 0 || matched.size() <= limit) {
            return matched;
        }
        return matched.subList(0, limit);
    }

    @Override
    public long countVisibleArchived(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        return scan().stream()
                .filter(task -> task.visibleTo(userId))
                .filter(TodoTask::archived)
                .count();
    }

    @Override
    public List<TodoTask> findByQuery(TodoQuery query) {
        long now = System.currentTimeMillis();
        List<TodoTask> matched = scan().stream()
                .filter(task -> matchesScope(task, query))
                .filter(task -> matchesStatus(task, query))
                .filter(task -> matchesPriority(task, query))
                .filter(task -> matchesKeyword(task, query))
                .filter(task -> matchesOverdue(task, query, now))
                .filter(task -> matchesArchived(task, query))
                .sorted(TASK_ORDER)
                .toList();
        return paged(matched, query.page(), query.size());
    }

    @Override
    public long countByQuery(TodoQuery query) {
        long now = System.currentTimeMillis();
        return scan().stream()
                .filter(task -> matchesScope(task, query))
                .filter(task -> matchesStatus(task, query))
                .filter(task -> matchesPriority(task, query))
                .filter(task -> matchesKeyword(task, query))
                .filter(task -> matchesOverdue(task, query, now))
                .filter(task -> matchesArchived(task, query))
                .count();
    }

    @Override
    public List<TodoTask> findAllByOwnerId(String ownerId, int limit) {
        List<TodoTask> owned = scan().stream()
                .filter(task -> task.ownerId().equals(ownerId))
                .sorted(TASK_ORDER)
                .toList();
        if (limit <= 0 || owned.size() <= limit) {
            return owned;
        }
        return owned.subList(0, limit);
    }

    /** 归档过滤：查询未指定（null）时不过滤，指定 true/false 时精确匹配。 */
    private boolean matchesArchived(TodoTask task, TodoQuery query) {
        return query.archived() == null || task.archived() == query.archived();
    }

    @Override
    public long countByOwnerId(String ownerId) {
        return scan().stream().filter(task -> task.ownerId().equals(ownerId)).count();
    }

    @Override
    public void delete(TodoTask task) {
        if (task == null) {
            return;
        }
        documents.delete(COLLECTION, task.id());
    }

    private boolean matchesScope(TodoTask task, TodoQuery query) {
        if (query.visibleTo() != null && !query.visibleTo().isBlank()) {
            return task.visibleTo(query.visibleTo());
        }
        if (query.ownerId() != null && !query.ownerId().isBlank()) {
            return query.ownerId().equals(task.ownerId());
        }
        return true;
    }

    private boolean matchesStatus(TodoTask task, TodoQuery query) {
        if (query.status() == null) {
            return true;
        }
        return query.status() == task.status();
    }

    private boolean matchesPriority(TodoTask task, TodoQuery query) {
        if (query.priority() == null) {
            return true;
        }
        return query.priority() == task.priority();
    }

    private boolean matchesKeyword(TodoTask task, TodoQuery query) {
        if (!query.hasKeyword()) {
            return true;
        }
        String keyword = query.keyword().toLowerCase();
        return task.title().toLowerCase().contains(keyword)
                || task.remark().toLowerCase().contains(keyword);
    }

    private boolean matchesOverdue(TodoTask task, TodoQuery query, long now) {
        return !query.overdueOnly() || task.overdue(now);
    }

    private static <T> List<T> paged(List<T> source, int page, int size) {
        int from = Math.min(source.size(), (page - 1) * size);
        int to = Math.min(source.size(), from + size);
        if (from >= to) {
            return List.of();
        }
        return source.subList(from, to);
    }

    private List<TodoTask> scan() {
        List<TodoTask> all = new ArrayList<>();
        for (int page = 1; page <= MAX_SCAN_PAGES; page++) {
            List<Map<String, Object>> batch = documents.findAll(COLLECTION, page, SCAN_PAGE_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> document : batch) {
                all.add(toTask(document));
            }
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        return all;
    }

    /** 未完成优先，其次按截止时间升序（无截止时间排最后），最后按更新时间倒序。 */
    private static final Comparator<TodoTask> TASK_ORDER =
            Comparator.comparing((TodoTask task) -> task.status() == TodoStatus.DONE ? 1 : 0)
                    .thenComparing(task -> task.dueAt() == null ? Long.MAX_VALUE : task.dueAt())
                    .thenComparing(Comparator.comparingLong(TodoTask::updatedAt).reversed());

    private Map<String, Object> document(TodoTask task) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", task.id());
        document.put("ownerId", task.ownerId());
        document.put("title", task.title());
        document.put("remark", task.remark());
        document.put("priority", task.priority().code());
        document.put("status", task.status().code());
        document.put("dueAt", task.dueAt());
        document.put("completedAt", task.completedAt());
        document.put("source", task.source().code());
        document.put("assignedBy", task.assignedBy());
        document.put("createdAt", task.createdAt());
        document.put("updatedAt", task.updatedAt());
        List<Map<String, Object>> subTasks = new ArrayList<>();
        for (TodoSubTask item : task.subTasks()) {
            Map<String, Object> subTask = new LinkedHashMap<>();
            subTask.put("id", item.id());
            subTask.put("title", item.title());
            subTask.put("done", item.done());
            subTask.put("updatedAt", item.updatedAt());
            subTask.put("assigneeIds", new ArrayList<>(item.assigneeIds()));
            if (item.priority() != null) {
                subTask.put("priority", item.priority());
            }
            if (item.dueAt() != null) {
                subTask.put("dueAt", item.dueAt());
            }
            subTasks.add(subTask);
        }
        document.put("subTasks", subTasks);
        document.put("participantIds", new ArrayList<>(task.participantIds()));
        document.put("visibility", task.visibility());
        document.put("deptIds", new ArrayList<>(task.deptIds()));
        document.put("deptNames", new ArrayList<>(task.deptNames()));
        document.put("archived", task.archived());
        return document;
    }

    private TodoTask toTask(Map<String, Object> document) {
        TodoTask task = new TodoTask(
                string(document, "id"),
                string(document, "ownerId"),
                string(document, "title"),
                string(document, "remark"),
                TodoPriority.of(string(document, "priority")),
                TodoStatus.of(string(document, "status")),
                longValue(document, "dueAt"),
                longValue(document, "completedAt"),
                TodoSource.of(string(document, "source")),
                string(document, "assignedBy"),
                longValue(document, "createdAt") == null ? 0L : longValue(document, "createdAt"),
                longValue(document, "updatedAt") == null ? 0L : longValue(document, "updatedAt"),
                subTasks(document),
                participants(document)
        );
        task.assignVisibility(string(document, "visibility"),
                stringListValue(document, "deptIds"), stringListValue(document, "deptNames"));
        task.assignArchived(Boolean.TRUE.equals(document.get("archived")));
        return task;
    }

    /** 读取字符串列表字段；缺字段或类型不符时按空列表处理，保证旧数据兼容。 */
    private static List<String> stringListValue(Map<String, Object> document, String key) {
        Object raw = document.get(key);
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            String value = item == null ? "" : String.valueOf(item).trim();
            if (!value.isEmpty() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> participants(Map<String, Object> document) {
        Object raw = document.get("participantIds");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            String id = item == null ? "" : String.valueOf(item).trim();
            if (!id.isEmpty() && !result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<TodoSubTask> subTasks(Map<String, Object> document) {
        Object raw = document.get("subTasks");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<TodoSubTask> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> values = (Map<String, Object>) map;
            String title = string(values, "title");
            if (title == null || title.isBlank()) {
                continue;
            }
            String id = string(values, "id");
            Long updatedAt = longValue(values, "updatedAt");
            result.add(new TodoSubTask(
                    id == null || id.isBlank() ? Ids.next() : id,
                    title,
                    Boolean.TRUE.equals(values.get("done")),
                    updatedAt == null ? 0L : updatedAt,
                    assignees(values),
                    string(values, "priority"),
                    longValue(values, "dueAt")
            ));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> assignees(Map<String, Object> values) {
        Object raw = values.get("assigneeIds");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            String id = item == null ? "" : String.valueOf(item).trim();
            if (!id.isEmpty() && !result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private static String string(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Map<String, Object> document, String key) {
        Object value = document.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }
}
