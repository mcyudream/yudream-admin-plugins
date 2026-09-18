package online.yudream.base.plugin.minecraft.interfaces.request;

import java.util.List;

/**
 * Body of {@code POST /report/topology} (matched by address) and
 * {@code POST /report/servers/{serverId}/topology} (bound explicitly).
 *
 * <pre>
 * {
 *   "proxy": "velocity",
 *   "proxyVersion": "3.5.0",
 *   "addresses": ["play.example.com", "play.example.com:25565"],
 *   "reportedAt": 1757850000000,
 *   "servers": [
 *     {"name": "lobby", "address": "127.0.0.1:25566", "online": 3, "sensor": true, "defaultServer": true}
 *   ]
 * }
 * </pre>
 *
 * <p>{@code addresses} are the proxy's own public addresses. The address-matched endpoint uses them
 * to find the server entry to attach to; the per-server endpoint ignores them.
 */
public record MinecraftServerTopologyRequest(
        String proxy,
        String proxyVersion,
        List<String> addresses,
        Long reportedAt,
        List<Server> servers
) {

    public record Server(
            String name,
            String address,
            Integer online,
            Boolean sensor,
            Boolean defaultServer
    ) {
    }
}
