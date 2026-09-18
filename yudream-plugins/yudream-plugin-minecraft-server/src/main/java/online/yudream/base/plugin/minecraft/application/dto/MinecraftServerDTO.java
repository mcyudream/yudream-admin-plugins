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

        /**
         * 下发/展示地址。自动端口线路（{@code port <= 0}，Java 版由客户端自行 SRV 解析）
         * 与 Java 默认端口 25565 一律只输出 host，拼接 {@code :0} 会导致客户端无法连接。
         */
        public String address() {
            return port <= 0 || port == 25565 ? host : host + ":" + port;
        }
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
