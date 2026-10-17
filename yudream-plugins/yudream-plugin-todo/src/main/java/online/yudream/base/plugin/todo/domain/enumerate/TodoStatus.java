package online.yudream.base.plugin.todo.domain.enumerate;

/** 待办状态。 */
public enum TodoStatus {

    PENDING("pending"),
    DONE("done");

    private final String code;

    TodoStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static TodoStatus of(String raw) {
        if (raw == null || raw.isBlank()) {
            return PENDING;
        }
        String value = raw.trim().toLowerCase();
        for (TodoStatus status : values()) {
            if (status.code.equals(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        return PENDING;
    }
}
