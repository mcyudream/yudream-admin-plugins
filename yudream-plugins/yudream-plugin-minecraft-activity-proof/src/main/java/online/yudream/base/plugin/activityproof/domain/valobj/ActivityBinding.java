package online.yudream.base.plugin.activityproof.domain.valobj;

import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityBindingType;

/**
 * 活动绑定的达标核验方式：服务器时长检测（PLAYTIME）、平台表单提交（FORM）
 * 或题库答题达标（QUIZ，达标规则取自活动答题环节配置）。
 *
 * <p>{@code subServer} 只对 PLAYTIME 有意义：选中的服务器是群组服代理时，可以只按其中一台下游
 * 子服计时长，空字符串表示整服（不限子服）。两者不可混同——群组服下玩家的时间分散在多台子服上，
 * 整服口径会把它们加在一起。
 */
public record ActivityBinding(
        ActivityBindingType type,
        String serverId,
        String subServer,
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
        subServer = text(subServer);
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
            // 子服只约束时长检测；表单与答题绑定的服务器字段本身就没有意义。
            subServer = "";
            autoJoin = false;
        }
    }

    /** 整服口径的时长检测：不限子服。 */
    public static ActivityBinding playtime(String serverId, int minOnlineMinutes, boolean includeAfk,
                                           boolean autoJoin) {
        return playtime(serverId, "", minOnlineMinutes, includeAfk, autoJoin);
    }

    /** 指定子服的时长检测；{@code subServer} 为空表示整服。 */
    public static ActivityBinding playtime(String serverId, String subServer, int minOnlineMinutes,
                                           boolean includeAfk, boolean autoJoin) {
        return new ActivityBinding(ActivityBindingType.PLAYTIME, serverId, subServer, minOnlineMinutes,
                includeAfk, autoJoin, "", "");
    }

    public static ActivityBinding form(String formCode, String formName) {
        return new ActivityBinding(ActivityBindingType.FORM, "", "", 0, false, false, formCode, formName);
    }

    public static ActivityBinding quiz() {
        return new ActivityBinding(ActivityBindingType.QUIZ, "", "", 0, false, false, "", "");
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
