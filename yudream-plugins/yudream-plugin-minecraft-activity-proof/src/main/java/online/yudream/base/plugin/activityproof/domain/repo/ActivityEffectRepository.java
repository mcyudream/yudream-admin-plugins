package online.yudream.base.plugin.activityproof.domain.repo;

import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityEffect;

import java.util.List;
import java.util.Optional;

public interface ActivityEffectRepository {

    Optional<ActivityEffect> effect(String id);

    /** 按活动查效果提交，submittedAt 倒序由调用侧排序。 */
    List<ActivityEffect> effectsByActivity(String activityId, int page, int size);

    long countEffectsByActivity(String activityId);

    List<ActivityEffect> effectsBySubmitter(String userId, int page, int size);

    long countEffectsBySubmitter(String userId);

    /** 管理端列表：activityId/status 均可空，内存过滤后分页。 */
    List<ActivityEffect> effects(String activityId, String status, int page, int size);

    long countEffects(String activityId, String status);

    /** 全量扫描（分页拉取），供时间窗聚合等低频场景使用。 */
    List<ActivityEffect> allEffects();

    ActivityEffect save(ActivityEffect effect);

    void delete(String id);
}
