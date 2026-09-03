package online.yudream.base.plugin.activityproof.interfaces.request;

public record ActivityBindingRequest(
        String type,
        String serverId,
        Integer minOnlineMinutes,
        Boolean includeAfk,
        String formCode
) {
}
