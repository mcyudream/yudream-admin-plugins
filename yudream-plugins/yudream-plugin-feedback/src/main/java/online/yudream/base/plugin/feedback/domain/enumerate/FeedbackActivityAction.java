package online.yudream.base.plugin.feedback.domain.enumerate;

import java.util.Locale;

/**
 * 处理过程留痕的动作类型。
 *
 * <p>对应 GitHub Issue 时间线上的事件条目，与评论一起按时间合并渲染；
 * {@code label} 只作为服务端给出的兜底文案，具体展示由前端决定。
 */
public enum FeedbackActivityAction {

    SUBMIT("submit", "提交反馈"),
    UPDATE("update", "修改了反馈内容"),
    COMMENT("comment", "追加说明"),
    REPLY("reply", "官方回复"),
    NOTE("note", "记录内部备注"),
    STATUS("status", "变更状态"),
    ASSIGN("assign", "指派处理人"),
    UNASSIGN("unassign", "取消指派"),
    SEVERITY("severity", "调整严重程度"),
    ARCHIVE("archive", "归档");

    private final String code;
    private final String label;

    FeedbackActivityAction(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static FeedbackActivityAction of(String raw) {
        if (raw == null || raw.isBlank()) {
            return UPDATE;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (FeedbackActivityAction action : values()) {
            if (action.code.equals(normalized)) {
                return action;
            }
        }
        return UPDATE;
    }
}
