package online.yudream.base.plugin.todo.domain.enumerate;

/** 待办来源：用户自建或管理员派发。 */
public enum TodoSource {

    SELF("self"),
    ADMIN("admin");

    private final String code;

    TodoSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static TodoSource of(String raw) {
        if (raw == null || raw.isBlank()) {
            return SELF;
        }
        return "admin".equalsIgnoreCase(raw.trim()) ? ADMIN : SELF;
    }
}
