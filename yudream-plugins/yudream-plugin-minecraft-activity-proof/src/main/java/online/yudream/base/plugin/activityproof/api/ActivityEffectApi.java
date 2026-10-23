package online.yudream.base.plugin.activityproof.api;

import java.util.List;
import java.util.Optional;

/**
 * 活动平台对外最小 API：活动与已采纳活动效果的只读查询。
 * 消费方以 provided 依赖本插件并经 PluginContext.service(...) 调用；实现经 exposeService 注册。
 * 只允许增加方法与字段，不允许修改既有签名与语义。
 */
public interface ActivityEffectApi {

    /** 单个活动的全部已采纳效果。 */
    List<ActivityEffectSummary> acceptedEffects(String activityId);

    /** 活动开始/结束时间落在窗口内的活动的已采纳效果（按活动分组顺序返回，不保证全局排序）。 */
    List<ActivityEffectSummary> acceptedEffectsBetween(long fromInclusive, long toInclusive);

    /** 活动摘要（含参与人数统计）。 */
    Optional<ActivityBrief> activity(String activityId);

    /** 活动开始时间落在窗口内的已发布/已结束活动。 */
    List<ActivityBrief> activitiesBetween(long fromInclusive, long toInclusive);
}
