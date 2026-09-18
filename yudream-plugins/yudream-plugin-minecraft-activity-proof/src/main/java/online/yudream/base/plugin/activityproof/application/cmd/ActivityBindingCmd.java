package online.yudream.base.plugin.activityproof.application.cmd;

public record ActivityBindingCmd(
        String type,
        String serverId,
        String subServer,
        Integer minOnlineMinutes,
        Boolean includeAfk,
        Boolean autoJoin,
        String formCode
) {
}
