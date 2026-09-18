package online.yudream.base.plugin.minecraft.application.cmd;

import java.util.List;

/**
 * A proxy's downstream-server list, as reported by the bridge running on that proxy.
 *
 * <p>Every field is optional on the wire so that a newer bridge can add fields without breaking an
 * older plugin, and so that a partially filled report still lands rather than being rejected.
 */
public record MinecraftServerTopologyCmd(
        String proxy,
        String proxyVersion,
        Long reportedAt,
        List<Server> servers
) {

    public MinecraftServerTopologyCmd {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    public record Server(
            String name,
            String address,
            Integer online,
            Boolean sensor,
            Boolean defaultServer
    ) {
    }
}
