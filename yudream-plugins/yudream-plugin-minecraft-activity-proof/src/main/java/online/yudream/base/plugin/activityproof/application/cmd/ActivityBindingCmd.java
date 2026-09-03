package online.yudream.base.plugin.activityproof.application.cmd;

public record ActivityBindingCmd(
        String type,
        String serverId,
        Integer minOnlineMinutes,
        Boolean includeAfk,
        String formCode
) {
}
