package online.yudream.base.plugin.minecraft.interfaces.res;

import java.util.List;

public record MinecraftServerRes(
        String id,
        String name,
        String descriptionMarkdown,
        boolean enabled,
        int sort,
        List<EndpointRes> endpoints,
        List<SeasonRes> seasons,
        SeasonRes currentSeason,
        MinecraftServerStatusRes status,
        MapRes map,
        TopologyRes topology,
        long createdAt,
        long updatedAt
) {

    public record TopologyRes(
            String proxy,
            String proxyVersion,
            long reportedAt,
            boolean reported,
            int onlinePlayers,
            long sensorCount,
            List<SubServerRes> servers
    ) {
    }

    public record SubServerRes(
            String name,
            String address,
            int online,
            boolean sensor,
            boolean defaultServer,
            int sort
    ) {
    }

    public record MapRes(String fileId, String originalName, boolean publicAccess, String externalUrl) {}

    public record EndpointRes(
            String id,
            String name,
            String host,
            int port,
            String edition,
            boolean primaryLine,
            boolean enabled,
            int sort
    ) {
    }

    public record SeasonRes(
            String id,
            String name,
            String description,
            Long startedAt,
            Long endedAt,
            boolean current,
            int sort,
            ModpackBindingRes modpackBinding
    ) {
    }
}
