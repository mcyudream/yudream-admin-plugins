package online.yudream.base.plugin.activityproof.application.dto;

public record ActivityBindingDTO(
        String type,
        String serverId,
        String serverName,
        int minOnlineMinutes,
        boolean includeAfk,
        String formCode,
        String formName,
        String requirementText
) {
}
