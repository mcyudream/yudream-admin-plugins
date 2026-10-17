package online.yudream.base.plugin.todo.application.assembler;

import java.util.List;
import java.util.function.Function;
import online.yudream.base.plugin.todo.application.dto.TodoSubTaskDTO;
import online.yudream.base.plugin.todo.application.dto.TodoTaskDTO;
import online.yudream.base.plugin.todo.application.dto.TodoTemplateDTO;
import online.yudream.base.plugin.todo.domain.aggregate.TodoTask;
import online.yudream.base.plugin.todo.domain.valobj.TodoSubTask;
import online.yudream.base.plugin.todo.infrastructure.TimeSupport;

/**
 * 领域聚合到应用层 DTO 的转换。
 *
 * <p>{@code viewerId} 决定「归属人 / 参与人 / 能否改参与人」这些与请求者相关的派生字段，
 * 避免前端拿着归属用户 ID 自己推测权限。管理端传入 {@code viewerAdmin = true}。
 */
public class TodoTaskAssembler {

    public TodoTaskDTO toDTO(TodoTask task, Function<String, String> ownerNameLoader,
                             String viewerId, boolean viewerAdmin) {
        String ownerName = nameOf(ownerNameLoader, task.ownerId());
        String assignedByName = task.assignedBy() == null || task.assignedBy().isBlank()
                ? ""
                : nameOf(ownerNameLoader, task.assignedBy());
        boolean owned = viewerId != null && !viewerId.isBlank() && viewerId.equals(task.ownerId());
        boolean canManageParticipants = viewerAdmin
                || (viewerId != null && !viewerId.isBlank() && viewerId.equals(task.creatorId()));
        return new TodoTaskDTO(
                task.id(),
                task.ownerId(),
                ownerName,
                task.title(),
                task.remark(),
                task.priority().code(),
                task.status().code(),
                task.dueAt(),
                TimeSupport.formatDateTime(task.dueAt()),
                task.completedAt(),
                TimeSupport.formatDateTime(task.completedAt()),
                task.source().code(),
                task.assignedBy(),
                assignedByName,
                task.overdue(System.currentTimeMillis()),
                task.createdAt(),
                TimeSupport.formatDateTime(task.createdAt()),
                task.updatedAt(),
                TimeSupport.formatDateTime(task.updatedAt()),
                task.subTasks().stream()
                        .map(item -> new TodoSubTaskDTO(item.id(), item.title(), item.done(), item.assigneeIds(), item.priority(), item.dueAt(),
                                item.updatedAt(), TimeSupport.formatDateTime(item.updatedAt())))
                        .toList(),
                task.subTaskTotal(),
                task.subTaskDone(),
                task.participantIds(),
                task.visibility(),
                task.deptIds(),
                task.deptNames(),
                task.archived(),
                owned,
                canManageParticipants,
                mySubTaskIds(viewerId, task)
        );
    }

    public TodoTemplateDTO toTemplate(TodoTask task, boolean inMyList, Function<String, String> ownerNameLoader) {
        return new TodoTemplateDTO(
                task.id(),
                task.title(),
                task.remark(),
                task.priority().code(),
                task.subTaskTotal(),
                nameOf(ownerNameLoader, task.ownerId()),
                task.visibility(),
                inMyList
        );
    }

    /** 当前请求者负责的小任务 ID 列表；管理端 viewer 是管理员本人，该字段对管理端无意义。 */
    private static List<String> mySubTaskIds(String viewerId, TodoTask task) {
        if (viewerId == null || viewerId.isBlank()) {
            return List.of();
        }
        return task.subTasks().stream()
                .filter(item -> item.assigneeIds().contains(viewerId))
                .map(TodoSubTask::id)
                .toList();
    }

    private static String nameOf(Function<String, String> loader, String userId) {
        if (loader == null || userId == null || userId.isBlank()) {
            return "";
        }
        String name = loader.apply(userId);
        return name == null ? "" : name;
    }
}
