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

    /** 与时间窗口重叠（开始或结束任一时刻在窗口内）的活动的已采纳效果。 */
    List<ActivityEffectSummary> acceptedEffectsBetween(long fromInclusive, long toInclusive);

    /** 活动摘要（含参与人数统计）。 */
    Optional<ActivityBrief> activity(String activityId);

    /** 与时间窗口重叠（开始或结束任一时刻在窗口内）的已发布/已结束活动，已结束但与窗口有交集的活动同样返回。 */
    List<ActivityBrief> activitiesBetween(long fromInclusive, long toInclusive);
}
