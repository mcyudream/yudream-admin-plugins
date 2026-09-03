package online.yudream.base.plugin.activityproof.interfaces.request;

import java.util.List;

public record ActivitySaveRequest(
        String id,
        String title,
        String summary,
        String description,
        String coverUrl,
        Long signupStart,
        Long signupEnd,
        Long activityStart,
        Long activityEnd,
        String deptMode,
        List<String> allowedDeptIds,
        List<ActivityBindingRequest> bindings
) {
}
