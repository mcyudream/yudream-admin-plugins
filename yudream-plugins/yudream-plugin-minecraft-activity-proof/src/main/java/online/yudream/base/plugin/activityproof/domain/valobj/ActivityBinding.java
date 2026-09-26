package online.yudream.base.plugin.activityproof.domain.valobj;

import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityBindingType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 活动绑定的达标核验方式：服务器时长检测（PLAYTIME）、平台表单提交（FORM）、
 * 题库答题达标（QUIZ，达标规则取自活动答题环节配置）或高级自定义计分（ADVANCED）。
 *
 * <p>{@code subServer} 只对 PLAYTIME 有意义：选中的服务器是群组服代理时，可以只按其中一台下游
 * 子服计时长，空字符串表示整服（不限子服）。两者不可混同——群组服下玩家的时间分散在多台子服上，
 * 整服口径会把它们加在一起。
 *
 * <p>ADVANCED 的 {@code params}/{@code expression}/{@code minScore}/{@code maxScore} 定义计分规则：
 * 每个参数暴露成单字母变量，计算式求出的综合分落在达标区间 [minScore, maxScore] 内即通过
 * （maxScore 为 null 表示不设上限）。
 */
public record ActivityBinding(
        ActivityBindingType type,
        String serverId,
        String subServer,
        int minOnlineMinutes,
        boolean includeAfk,
        boolean autoJoin,
        String formCode,
        String formName,
        List<ActivityBindingParam> params,
        String expression,
        double minScore,
        Double maxScore
) {
    /** 高级自定义计分的参数上限：单字母变量留出书写余量，8 个足够组合多服多环节。 */
    public static final int MAX_PARAMS = 8;

    public ActivityBinding {
        if (type == null) {
            throw new IllegalArgumentException("核验方式不能为空");
        }
        serverId = text(serverId);
        subServer = text(subServer);
        formCode = text(formCode);
        formName = text(formName);
        minOnlineMinutes = Math.max(minOnlineMinutes, 0);
        params = params == null ? List.of() : List.copyOf(params);
        expression = text(expression);
        if (type == ActivityBindingType.PLAYTIME && serverId.isBlank()) {
            throw new IllegalArgumentException("时长检测必须选择服务器");
        }
        if (type == ActivityBindingType.FORM && formCode.isBlank()) {
            throw new IllegalArgumentException("表单核验必须选择表单");
        }
        if (type != ActivityBindingType.PLAYTIME) {
            // 子服与自动参与只约束时长检测；表单、答题、高级自定义绑定的服务器字段本身就没有意义。
            subServer = "";
            autoJoin = false;
        }
        if (type == ActivityBindingType.ADVANCED) {
            if (params.isEmpty()) {
                throw new IllegalArgumentException("高级自定义计分至少需要一个计分参数");
            }
            if (params.size() > MAX_PARAMS) {
                throw new IllegalArgumentException("计分参数最多 " + MAX_PARAMS + " 个");
            }
            Set<String> keys = new LinkedHashSet<>();
            for (ActivityBindingParam param : params) {
                if (!keys.add(param.key())) {
                    throw new IllegalArgumentException("计分参数变量名重复：" + param.key());
                }
            }
            // 语法与未知变量在构造时即校验，管理端保存时就能拿到明确错误
            ScoreFormula.parse(expression, keys);
            if (!(minScore >= 0)) {
                throw new IllegalArgumentException("达标分数线不能为负数");
            }
            if (maxScore != null && maxScore < minScore) {
                throw new IllegalArgumentException("达标分数上限不能低于下限");
            }
        } else {
            // 计分字段只属于高级自定义，其余核验方式一律收敛为缺省，文档与改造前保持一致
            params = List.of();
            expression = "";
            minScore = 0;
            maxScore = null;
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
                includeAfk, autoJoin, "", "", List.of(), "", 0, null);
    }

    public static ActivityBinding form(String formCode, String formName) {
        return new ActivityBinding(ActivityBindingType.FORM, "", "", 0, false, false, formCode, formName,
                List.of(), "", 0, null);
    }

    public static ActivityBinding quiz() {
        return new ActivityBinding(ActivityBindingType.QUIZ, "", "", 0, false, false, "", "",
                List.of(), "", 0, null);
    }

    /** 高级自定义计分：参数表 + 计算式 + 达标区间；{@code maxScore} 为 null 表示不设上限。 */
    public static ActivityBinding advanced(List<ActivityBindingParam> params, String expression,
                                           double minScore, Double maxScore) {
        return new ActivityBinding(ActivityBindingType.ADVANCED, "", "", 0, false, false, "", "",
                params, expression, minScore, maxScore);
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

    public boolean isAdvanced() {
        return type == ActivityBindingType.ADVANCED;
    }

    /** 是否只按某一台子服计时长；false 表示整服口径。 */
    public boolean scopedToSubServer() {
        return isPlaytime() && !subServer.isEmpty();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
