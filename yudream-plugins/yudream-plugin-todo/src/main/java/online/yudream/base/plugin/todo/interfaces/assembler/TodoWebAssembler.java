package online.yudream.base.plugin.todo.interfaces.assembler;

import online.yudream.base.plugin.todo.application.cmd.TodoTaskSaveCmd;
import online.yudream.base.plugin.todo.interfaces.request.TodoTaskSaveRequest;

/** 请求体到应用层命令的转换（只做边界解析，不含业务规则）。 */
public class TodoWebAssembler {

    public TodoTaskSaveCmd toCmd(TodoTaskSaveRequest request) {
        return new TodoTaskSaveCmd(
                request.title(),
                request.remark(),
                request.priority(),
                request.dueAt(),
                request.subTasks(),
                request.participantIds(),
                request.visibility(),
                request.deptIds()
        );
    }
}
