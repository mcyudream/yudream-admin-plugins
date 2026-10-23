package online.yudream.base.plugin.aioffice.infrastructure.support;

import online.yudream.base.plugin.activityproof.api.ActivityBrief;
import online.yudream.base.plugin.activityproof.api.ActivityEffectApi;
import online.yudream.base.plugin.activityproof.api.ActivityEffectSummary;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.util.List;
import java.util.Optional;

/**
 * 活动平台软依赖桥：API 类型引用集中本类，先 dependencyAvailable 无类检查再解析，
 * provider 缺失/禁用时降级为空结果；API 对象每次现取，不跨 reload 缓存。
 */
public final class ActivityPlatformBridge {

    public static final String PLUGIN_CODE = "minecraft-activity-proof";

    private final PluginContext context;

    public ActivityPlatformBridge(PluginContext context) {
        this.context = context;
    }

    public boolean available() {
        return context != null && context.dependencyAvailable(PLUGIN_CODE);
    }

    public Optional<ActivityBrief> activity(String activityId) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            return context.service(PLUGIN_CODE, ActivityEffectApi.class)
                    .flatMap(api -> api.activity(activityId));
        } catch (LinkageError e) {
            return Optional.empty();
        }
    }

    public List<ActivityBrief> activitiesBetween(long fromInclusive, long toInclusive) {
        if (!available()) {
            return List.of();
        }
        try {
            return context.service(PLUGIN_CODE, ActivityEffectApi.class)
                    .map(api -> api.activitiesBetween(fromInclusive, toInclusive))
                    .orElse(List.of());
        } catch (LinkageError e) {
            return List.of();
        }
    }

    public List<ActivityEffectSummary> acceptedEffects(String activityId) {
        if (!available()) {
            return List.of();
        }
        try {
            return context.service(PLUGIN_CODE, ActivityEffectApi.class)
                    .map(api -> api.acceptedEffects(activityId))
                    .orElse(List.of());
        } catch (LinkageError e) {
            return List.of();
        }
    }

    public List<ActivityEffectSummary> acceptedEffectsBetween(long fromInclusive, long toInclusive) {
        if (!available()) {
            return List.of();
        }
        try {
            return context.service(PLUGIN_CODE, ActivityEffectApi.class)
                    .map(api -> api.acceptedEffectsBetween(fromInclusive, toInclusive))
                    .orElse(List.of());
        } catch (LinkageError e) {
            return List.of();
        }
    }
}
