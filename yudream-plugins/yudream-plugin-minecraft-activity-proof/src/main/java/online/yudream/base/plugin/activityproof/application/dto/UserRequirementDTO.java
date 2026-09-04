package online.yudream.base.plugin.activityproof.application.dto;

/**
 * 用户端达标条件条目：在纯文案之外携带结构化信息，
 * 表单类条件可由前端跳转到对应的表单填写页。
 */
public record UserRequirementDTO(
        String type,
        String text,
        String formCode,
        String formName
) {
}
