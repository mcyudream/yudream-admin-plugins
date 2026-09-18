package online.yudream.base.plugin.minecraft.domain.aggregate;

import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftSubServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The downstream servers behind one proxy, last reported by the bridge on that proxy.
 *
 * <p>This is a snapshot, not configuration: it is replaced wholesale on every report and never
 * edited by hand, so that what the Admin shows always reflects the proxy's real server list.
 */
public record MinecraftServerTopology(
        String serverId,
        String proxy,
        String proxyVersion,
        long reportedAt,
        List<MinecraftSubServer> servers
) {

    private static final int MAX_SUB_SERVERS = 200;

    public MinecraftServerTopology {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("拓扑缺少服务器 ID");
        }
        serverId = serverId.trim();
        proxy = normalizeLabel(proxy);
        proxyVersion = normalizeLabel(proxyVersion);
        servers = normalize(servers);
    }

    public static MinecraftServerTopology empty(String serverId) {
        return new MinecraftServerTopology(serverId, "", "", 0L, List.of());
    }

    /** True when the bridge has ever reported this proxy's server list. */
    public boolean reported() {
        return reportedAt > 0;
    }

    public int onlinePlayers() {
        return servers.stream().mapToInt(MinecraftSubServer::online).sum();
    }

    public long sensorCount() {
        return servers.stream().filter(MinecraftSubServer::sensor).count();
    }

    private static List<MinecraftSubServer> normalize(List<MinecraftSubServer> servers) {
        if (servers == null || servers.isEmpty()) {
            return List.of();
        }
        List<MinecraftSubServer> items = new ArrayList<>(servers);
        items.sort(Comparator.comparingInt(MinecraftSubServer::sort).thenComparing(MinecraftSubServer::name));
        // The proxy is authoritative, but a malformed report must not be able to pin unbounded rows.
        if (items.size() > MAX_SUB_SERVERS) {
            items = items.subList(0, MAX_SUB_SERVERS);
        }
        return List.copyOf(items);
    }

    private static String normalizeLabel(String value) {
        String label = value == null ? "" : value.trim();
        return label.length() > 64 ? label.substring(0, 64) : label;
    }
}
