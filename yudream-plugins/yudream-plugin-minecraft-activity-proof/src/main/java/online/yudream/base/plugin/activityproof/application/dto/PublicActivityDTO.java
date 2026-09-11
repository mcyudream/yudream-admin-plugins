package online.yudream.base.plugin.activityproof.application.dto;

/**
 * 公开站活动详情：只暴露匿名可见字段，不含部门名单与参与状态。
 */
public record PublicActivityDTO(
        String id,
        String title,
        String summary,
        String description,
        String coverUrl,
        String url,
        long signupStart,
        long signupEnd,
        long activityStart,
        long activityEnd,
        String status,
        String statusKey,
        String statusText,
        String meta
) {
}
