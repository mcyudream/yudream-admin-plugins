package online.yudream.base.plugin.activityproof.domain.aggregate;

import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityBindingType;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityDeptMode;
import online.yudream.base.plugin.activityproof.domain.enumerate.ActivityStatus;
import online.yudream.base.plugin.activityproof.domain.valobj.ActivityBinding;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record Activity(
        String id,
        String title,
        String summary,
        String description,
        String coverUrl,
        long signupStart,
        long signupEnd,
        long activityStart,
        long activityEnd,
        ActivityStatus status,
        ActivityDeptMode deptMode,
        List<String> allowedDeptIds,
        List<ActivityBinding> bindings,
        String createdBy,
        long createdAt,
        long updatedAt,
        long publishedAt
) {
    public Activity {
        title = require(title, "活动标题不能为空");
        summary = text(summary);
        description = text(description);
        coverUrl = text(coverUrl);
        status = status == null ? ActivityStatus.DRAFT : status;
        deptMode = deptMode == null ? ActivityDeptMode.ALL : deptMode;
        allowedDeptIds = allowedDeptIds == null ? List.of() : List.copyOf(cleanIds(allowedDeptIds));
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        createdBy = text(createdBy);
        signupStart = Math.max(signupStart, 0);
        signupEnd = Math.max(signupEnd, 0);
        activityStart = Math.max(activityStart, 0);
        activityEnd = Math.max(activityEnd, 0);
        if (signupStart > 0 && signupEnd > 0 && signupEnd < signupStart) {
            throw new IllegalArgumentException("报名结束时间不能早于开始时间");
        }
        if (activityStart > 0 && activityEnd > 0 && activityEnd < activityStart) {
            throw new IllegalArgumentException("活动结束时间不能早于开始时间");
        }
        if (deptMode == ActivityDeptMode.DEPTS && allowedDeptIds.isEmpty()) {
            throw new IllegalArgumentException("限制部门参与时请选择至少一个部门");
        }
        // 紧凑构造器内字段尚未赋值，必须基于参数判断，不能调用读取字段的实例方法
        boolean playtimeBound = bindings.stream().anyMatch(binding -> binding.type() == ActivityBindingType.PLAYTIME);
        if (playtimeBound && (activityStart <= 0 || activityEnd <= activityStart)) {
            throw new IllegalArgumentException("绑定时长检测的活动必须配置有效的活动起止时间");
        }
    }

    public static Activity create(String title, String summary, String description, String coverUrl,
                                  long signupStart, long signupEnd, long activityStart, long activityEnd,
                                  ActivityDeptMode deptMode, List<String> allowedDeptIds,
                                  List<ActivityBinding> bindings, String createdBy) {
        long now = System.currentTimeMillis();
        return new Activity(UUID.randomUUID().toString(), title, summary, description, coverUrl,
                signupStart, signupEnd, activityStart, activityEnd,
                ActivityStatus.DRAFT, deptMode, allowedDeptIds, bindings, createdBy, now, now, 0);
    }

    public Activity update(String title, String summary, String description, String coverUrl,
                           long signupStart, long signupEnd, long activityStart, long activityEnd,
                           ActivityDeptMode deptMode, List<String> allowedDeptIds, List<ActivityBinding> bindings) {
        if (status == ActivityStatus.CLOSED) {
            throw new IllegalArgumentException("已结束的活动不能再编辑");
        }
        return new Activity(id, title, summary, description, coverUrl,
                signupStart, signupEnd, activityStart, activityEnd,
                status, deptMode, allowedDeptIds, bindings, createdBy, createdAt, System.currentTimeMillis(), publishedAt);
    }

    public Activity publish() {
        if (status != ActivityStatus.DRAFT) {
            throw new IllegalArgumentException("只有草稿状态的活动可以发布");
        }
        long now = System.currentTimeMillis();
        return new Activity(id, title, summary, description, coverUrl,
                signupStart, signupEnd, activityStart, activityEnd,
                ActivityStatus.PUBLISHED, deptMode, allowedDeptIds, bindings, createdBy, createdAt, now, now);
    }

    public Activity close() {
        if (status != ActivityStatus.PUBLISHED) {
            throw new IllegalArgumentException("只有已发布的活动可以结束");
        }
        return new Activity(id, title, summary, description, coverUrl,
                signupStart, signupEnd, activityStart, activityEnd,
                ActivityStatus.CLOSED, deptMode, allowedDeptIds, bindings, createdBy, createdAt,
                System.currentTimeMillis(), publishedAt);
    }

    public boolean hasPlaytimeBinding() {
        return bindings.stream().anyMatch(binding -> binding.type() == ActivityBindingType.PLAYTIME);
    }

    public String firstPlaytimeServerId() {
        return bindings.stream()
                .filter(binding -> binding.type() == ActivityBindingType.PLAYTIME)
                .map(ActivityBinding::serverId)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    public boolean signupOpen(long now) {
        if (status != ActivityStatus.PUBLISHED) {
            return false;
        }
        if (signupStart > 0 && now < signupStart) {
            return false;
        }
        return signupEnd <= 0 || now <= signupEnd;
    }

    // 活动时间已过即视为结束，不等管理员手动 close；未配置结束时间的活动不按时间判定
    public boolean activityEnded(long now) {
        return activityEnd > 0 && now > activityEnd;
    }

    public boolean allowsDepartments(Set<String> userDeptIds) {
        if (deptMode == ActivityDeptMode.ALL) {
            return true;
        }
        if (userDeptIds == null || userDeptIds.isEmpty()) {
            return false;
        }
        Set<String> allowed = new LinkedHashSet<>(allowedDeptIds);
        return userDeptIds.stream().anyMatch(allowed::contains);
    }

    private static Set<String> cleanIds(List<String> ids) {
        Set<String> result = new LinkedHashSet<>();
        for (String id : ids) {
            String value = text(id);
            if (!value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
