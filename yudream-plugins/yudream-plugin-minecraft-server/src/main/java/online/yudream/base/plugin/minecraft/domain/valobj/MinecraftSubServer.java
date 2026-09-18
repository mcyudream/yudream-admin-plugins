package online.yudream.base.plugin.minecraft.domain.valobj;

/**
 * One downstream server behind a proxy, as reported by the bridge running on that proxy.
 *
 * <p>The Admin host cannot discover these itself: a proxy's Server List Ping answers with the
 * proxy's own MOTD and player count and never lists its downstream servers. The only component
 * that knows the topology is the proxy, so every field here is reported rather than probed, and
 * the whole record is read-only on the Admin side.
 */
public record MinecraftSubServer(
        String name,
        String address,
        int online,
        boolean sensor,
        boolean defaultServer,
        int sort
) {

    public MinecraftSubServer {
        name = normalizeName(name);
        address = normalizeAddress(address);
        online = Math.max(online, 0);
    }

    /** True when this entry is the proxy's fallback server, shown so an operator can tell them apart. */
    public boolean isDefault() {
        return defaultServer;
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("子服名称不能为空");
        }
        String name = value.trim();
        if (name.length() > 64 || name.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("子服名称格式不正确");
        }
        return name;
    }

    private static String normalizeAddress(String value) {
        String address = value == null ? "" : value.trim();
        return address.length() > 255 ? address.substring(0, 255) : address;
    }
}
