package online.yudream.base.plugin.activityproof.domain.valobj;

import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityBindingType;

/**
 * 活动绑定的达标核验方式：服务器时长检测（PLAYTIME）、平台表单提交（FORM）
 * 或题库答题达标（QUIZ，达标规则取自活动答题环节配置）。
 */
public record ActivityBinding(
        ActivityBindingType type,
        String serverId,
        int minOnlineMinutes,
        boolean includeAfk,
        boolean autoJoin,
        String formCode,
        String formName
) {
    public ActivityBinding {
        if (type == null) {
            throw new IllegalArgumentException("核验方式不能为空");
        }
        serverId = text(serverId);
        formCode = text(formCode);
        formName = text(formName);
        minOnlineMinutes = Math.max(minOnlineMinutes, 0);
        if (type == ActivityBindingType.PLAYTIME && serverId.isBlank()) {
            throw new IllegalArgumentException("时长检测必须选择服务器");
        }
        if (type == ActivityBindingType.FORM && formCode.isBlank()) {
            throw new IllegalArgumentException("表单核验必须选择表单");
        }
        if (type != ActivityBindingType.PLAYTIME) {
            autoJoin = false;
        }
    }

    public static ActivityBinding playtime(String serverId, int minOnlineMinutes, boolean includeAfk,
                                           boolean autoJoin) {
        return new ActivityBinding(ActivityBindingType.PLAYTIME, serverId, minOnlineMinutes, includeAfk, autoJoin, "", "");
    }

    public static ActivityBinding form(String formCode, String formName) {
        return new ActivityBinding(ActivityBindingType.FORM, "", 0, false, false, formCode, formName);
    }

    public static ActivityBinding quiz() {
        return new ActivityBinding(ActivityBindingType.QUIZ, "", 0, false, false, "", "");
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

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
