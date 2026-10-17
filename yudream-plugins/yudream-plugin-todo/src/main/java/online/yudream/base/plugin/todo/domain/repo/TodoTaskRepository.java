package online.yudream.base.plugin.todo.domain.repo;

import java.util.List;
import java.util.Optional;
import online.yudream.base.plugin.todo.domain.aggregate.TodoTask;
import online.yudream.base.plugin.todo.domain.valobj.TodoQuery;

/** 待办任务仓储契约。 */
public interface TodoTaskRepository {

    TodoTask save(TodoTask task);

    Optional<TodoTask> findById(String id);

    /** 按归属查询单条：用户端与管理员端都通过「资源 ID + 归属」约束，避免跨用户访问。 */
    Optional<TodoTask> findByIdAndOwnerId(String id, String ownerId);

    /** 归属人或参与人可见的单条查询：参与人可推进任务，但不能编辑或删除。 */
    Optional<TodoTask> findByIdVisibleTo(String id, String userId);

    List<TodoTask> findByQuery(TodoQuery query);

    long countByQuery(TodoQuery query);

    /** 按归属全量扫描，用于个人概览与主页卡片统计。 */
    List<TodoTask> findAllByOwnerId(String ownerId, int limit);

    /** 归属 + 参与的全量扫描：个人清单、概览与日程都包含协作任务。 */
    List<TodoTask> findAllVisibleTo(String userId, int limit);

    /** 对指定用户可见且已归档的任务数：概览统计用，归档任务不进默认清单但仍是用户数据。 */
    long countVisibleArchived(String userId);

    long countByOwnerId(String ownerId);

    void delete(TodoTask task);
}
