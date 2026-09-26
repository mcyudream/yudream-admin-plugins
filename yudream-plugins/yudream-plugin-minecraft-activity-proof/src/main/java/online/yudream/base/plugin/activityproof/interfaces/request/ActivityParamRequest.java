package online.yudream.base.plugin.activityproof.interfaces.request;

public record ActivityParamRequest(
        String type,
        String label,
        String serverId,
        String subServer,
        Boolean includeAfk,
        String formCode
) {
}
