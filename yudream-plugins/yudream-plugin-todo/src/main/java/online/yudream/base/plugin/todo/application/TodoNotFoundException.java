package online.yudream.base.plugin.todo.application;

/** 业务异常：目标资源不存在或不属于当前数据范围。 */
public class TodoNotFoundException extends RuntimeException {

    public TodoNotFoundException(String message) {
        super(message);
    }
}
