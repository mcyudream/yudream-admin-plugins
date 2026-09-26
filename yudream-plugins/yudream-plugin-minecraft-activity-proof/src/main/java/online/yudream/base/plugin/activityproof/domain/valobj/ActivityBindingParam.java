package online.yudream.base.plugin.activityproof.domain.valobj;

import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityBindingType;

import java.util.Locale;

/**
 * 高级自定义计分的参数项：把一个可量化数据源暴露成单字母变量（{@code key}）供计算式引用。
 *
 * <p>{@code type} 复用核验方式枚举：PLAYTIME 取活动时段在线分钟数（AFK 口径由 {@code includeAfk}
 * 决定），FORM 取活动周期内是否提交（1/0），QUIZ 取活动答题的答对题数。
 */
public record ActivityBindingParam(
        String key,
        String label,
        ActivityBindingType type,
        String serverId,
        String subServer,
        boolean includeAfk,
        String formCode,
        String formName
) {
    public ActivityBindingParam {
        key = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        label = text(label);
        serverId = text(serverId);
        subServer = text(subServer);
        formCode = text(formCode);
        formName = text(formName);
        if (!key.matches("[a-z]")) {
            throw new IllegalArgumentException("计分参数变量名必须是单个字母 a-z");
        }
        if (type == null) {
            throw new IllegalArgumentException("计分参数类型不能为空");
        }
        if (type == ActivityBindingType.PLAYTIME && serverId.isBlank()) {
            throw new IllegalArgumentException("计分参数「" + key + "」需要选择服务器");
        }
        if (type == ActivityBindingType.FORM && formCode.isBlank()) {
            throw new IllegalArgumentException("计分参数「" + key + "」需要选择表单");
        }
        // 来源字段只对各自类型有意义：非时长参数不带服务器/子服/AFK 口径，非表单参数不带表单
        if (type != ActivityBindingType.PLAYTIME) {
            serverId = "";
            subServer = "";
            includeAfk = false;
        }
        if (type != ActivityBindingType.FORM) {
            formCode = "";
            formName = "";
        }
    }

    public boolean isPlaytime() {
        return type == ActivityBindingType.PLAYTIME;
    }

    public boolean isForm() {
        return type == ActivityBindingType.FORM;
    }

    public boolean isQuiz() {
        return type == ActivityBindingType.QUIZ;
    }

    /** 是否只按某一台子服计时长；false 表示整服口径。 */
    public boolean scopedToSubServer() {
        return isPlaytime() && !subServer.isEmpty();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
