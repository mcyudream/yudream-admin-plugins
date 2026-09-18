package online.yudream.base.plugin.activityproof.application.dto;

public record ActivityBindingDTO(
        String type,
        String serverId,
        String serverName,
        String subServer,
        int minOnlineMinutes,
        boolean includeAfk,
        boolean autoJoin,
        String formCode,
        String formName,
        String requirementText
) {
}
