package online.yudream.base.plugin.activityproof.application.cmd;

import java.util.List;

public record ActivitySaveCmd(
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
        List<ActivityBindingCmd> bindings
) {
}
