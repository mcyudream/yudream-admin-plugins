package online.yudream.base.plugin.todo.domain.enumerate;

/** 待办优先级：数值越大越紧急。 */
public enum TodoPriority {

    LOW("low"),
    NORMAL("normal"),
    HIGH("high"),
    URGENT("urgent");

    private final String code;

    TodoPriority(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public int weight() {
        return ordinal();
    }

    public static TodoPriority of(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        String value = raw.trim().toLowerCase();
        for (TodoPriority priority : values()) {
            if (priority.code.equals(value) || priority.name().equalsIgnoreCase(value)) {
                return priority;
            }
        }
        return NORMAL;
    }
}
