package online.yudream.base.plugin.activityproof.application.service;

import online.yudream.base.plugin.activityproof.api.ActivityBrief;
import online.yudream.base.plugin.activityproof.api.ActivityEffectApi;
import online.yudream.base.plugin.activityproof.api.ActivityEffectSummary;
import online.yudream.base.plugin.activityproof.domain.aggregate.Activity;
import online.yudream.base.plugin.activityproof.domain.aggregate.ActivityEffect;
import online.yudream.base.plugin.activityproof.domain.repo.ActivityEffectRepository;
import online.yudream.base.plugin.activityproof.domain.repo.ActivityProofRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** ActivityEffectApi 的只读实现：供经 exposeService 注册后由消费方跨插件调用。 */
public class ActivityEffectApiService implements ActivityEffectApi {

    private final ActivityProofRepository repository;
    private final ActivityEffectRepository effects;

    public ActivityEffectApiService(ActivityProofRepository repository, ActivityEffectRepository effects) {
        this.repository = repository;
        this.effects = effects;
    }

    @Override
    public List<ActivityEffectSummary> acceptedEffects(String activityId) {
        List<ActivityEffectSummary> result = new ArrayList<>();
        for (ActivityEffect effect : effects.effectsByActivity(activityId, 1, Integer.MAX_VALUE)) {
            if (effect.accepted()) {
                result.add(toSummary(effect));
            }
        }
        return result;
    }

    @Override
    public List<ActivityEffectSummary> acceptedEffectsBetween(long fromInclusive, long toInclusive) {
        List<ActivityEffectSummary> result = new ArrayList<>();
        for (ActivityEffect effect : effects.allEffects()) {
            if (!effect.accepted() || effect.submittedAt() < fromInclusive || effect.submittedAt() > toInclusive) {
                continue;
            }
            result.add(toSummary(effect));
        }
        return result;
    }

    @Override
    public Optional<ActivityBrief> activity(String activityId) {
        return repository.activity(activityId).map(this::toBrief);
    }

    @Override
    public List<ActivityBrief> activitiesBetween(long fromInclusive, long toInclusive) {
        List<ActivityBrief> result = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            List<Activity> batch = repository.activities("", "", page, 200);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (Activity activity : batch) {
                long start = activity.activityStart() > 0 ? activity.activityStart() : activity.publishedAt();
                if (start >= fromInclusive && start <= toInclusive && activity.status() != null) {
                    result.add(toBrief(activity));
                }
            }
            if (batch.size() < 200) {
                break;
            }
        }
        return result;
    }

    private static ActivityEffectSummary toSummary(ActivityEffect effect) {
        List<ActivityEffectSummary.Attachment> attachments = new ArrayList<>();
        for (ActivityEffect.Attachment attachment : effect.attachments()) {
            attachments.add(new ActivityEffectSummary.Attachment(attachment.fileKey(), attachment.filename(),
                    attachment.contentType(), attachment.size(), attachment.kind(), attachment.caption()));
        }
        return new ActivityEffectSummary(effect.id(), effect.activityId(), effect.submittedBy(),
                effect.submittedAt(), effect.text(), attachments);
    }

    private ActivityBrief toBrief(Activity activity) {
        long participantCount;
        try {
            participantCount = repository.countParticipationsByActivity(activity.id());
        } catch (RuntimeException e) {
            participantCount = 0;
        }
        return new ActivityBrief(activity.id(), activity.title(), activity.summary(), activity.description(),
                activity.coverUrl(), activity.status() == null ? "" : activity.status().name(),
                activity.signupStart(), activity.signupEnd(), activity.activityStart(), activity.activityEnd(),
                activity.publishedAt(), participantCount);
    }
}
