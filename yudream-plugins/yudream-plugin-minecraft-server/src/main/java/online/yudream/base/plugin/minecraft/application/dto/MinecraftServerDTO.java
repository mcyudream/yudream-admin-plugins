package online.yudream.base.plugin.minecraft.application.dto;

import java.util.List;

public record MinecraftServerDTO(
        String id,
        String name,
        String descriptionMarkdown,
        boolean enabled,
        int sort,
        List<EndpointDTO> endpoints,
        List<SeasonDTO> seasons,
        SeasonDTO currentSeason,
        MinecraftServerStatusDTO status,
        MinecraftServerMapDTO map,
        TopologyDTO topology,
        long createdAt,
        long updatedAt
) {

    /**
     * The proxy's reported downstream-server list. Absent for a server that is not a proxy, or a
     * proxy whose bridge has not reported yet.
     */
    public record TopologyDTO(
            String proxy,
            String proxyVersion,
            long reportedAt,
            List<SubServerDTO> servers
    ) {

        public boolean reported() {
            return reportedAt > 0;
        }
    }

    public record SubServerDTO(
            String name,
            String address,
            int online,
            boolean sensor,
            boolean defaultServer,
            int sort
    ) {
    }

    public record EndpointDTO(
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

    public record SeasonDTO(
            String id,
            String name,
            String description,
            Long startedAt,
            Long endedAt,
            boolean current,
            int sort,
            ModpackBindingDTO modpackBinding
    ) {
    }
}
