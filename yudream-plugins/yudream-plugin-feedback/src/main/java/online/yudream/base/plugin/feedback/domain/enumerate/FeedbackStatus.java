package online.yudream.base.plugin.feedback.domain.enumerate;

import java.util.Locale;

/**
 * 反馈受理状态。
 *
 * <p>沿用 GitHub Issue 的 open / closed 语义：{@link #PENDING}、{@link #PROCESSING} 属于未完结，
 * 其余三项属于已完结；是否未完结由 {@link #open()} 统一判定，避免各处自行拼状态集合。
 *
 * <p>{@link #RESOLVED} 与 {@link #REJECTED} 是「管理员已给出结论」的两个状态（{@link #concluded()}），
 * 提交人只能在这两个状态下归档；{@link #ARCHIVED} 是终态，不再回到处理流程。
 */
public enum FeedbackStatus {

    PENDING("pending", "待处理", true, false),
    PROCESSING("processing", "处理中", true, false),
    RESOLVED("resolved", "已解决", false, true),
    REJECTED("rejected", "不予处理", false, true),
    ARCHIVED("archived", "已归档", false, false);

    /**
     * 历史名称：1.0.0 用 closed / 已关闭 表示归档态，读取旧数据与旧前端入参时都要认。
     */
    private static final String LEGACY_ARCHIVED = "closed";

    private final String code;
    private final String label;
    private final boolean open;
    private final boolean concluded;

    FeedbackStatus(String code, String label, boolean open, boolean concluded) {
        this.code = code;
        this.label = label;
        this.open = open;
        this.concluded = concluded;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 是否属于未完结状态（对应 GitHub 的 open）。 */
    public boolean open() {
        return open;
    }

    /** 管理员是否已给出结论（已解决 / 不予处理）；决定提交人能否归档或重新打开。 */
    public boolean concluded() {
        return concluded;
    }

    /** 严格解析：空值按待处理，非法值抛错，用于入参校验。 */
    public static FeedbackStatus of(String raw) {
        if (raw == null || raw.isBlank()) {
            return PENDING;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (LEGACY_ARCHIVED.equals(normalized)) {
            return ARCHIVED;
        }
        for (FeedbackStatus status : values()) {
            if (status.code.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException(
                "反馈状态仅支持 pending / processing / resolved / rejected / archived");
    }

    /** 宽松解析：用于读取历史数据，无法识别时回退到待处理。 */
    public static FeedbackStatus lenient(String raw) {
        try {
            return of(raw);
        }
        catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
