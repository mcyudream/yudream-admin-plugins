package online.yudream.base.plugin.mcpanel.domain.valobj;

import java.net.InetAddress;

/**
 * 节点「玩家接入方式」：决定实例域名解析记录怎么写、以及玩家怎么连进来。
 *
 * <ul>
 *   <li>{@link #MODE_DIRECT} 节点直连：A/AAAA 指向节点对外地址（广告地址覆盖 &gt; 端点 host）；</li>
 *   <li>{@link #MODE_MANUAL} 自定义解析地址：由管理员自己填 IP（NAT 端口映射、DDNS、反代等场景）；</li>
 *   <li>{@link #MODE_FRP} FRP 入口：A/AAAA 指向 FRP 入口机，入口端口与实例端口按 1:1 约定；</li>
 *   <li>{@link #MODE_ENTRY} 单端口入口：所有实例共用入口端口，入口（mc-router）按玩家请求的
 *       域名转发到对应实例——A/AAAA 指向入口地址，端口用入口端口（默认 25565 免 SRV）；</li>
 *   <li>{@link #MODE_P2P} 启动器打洞：实例不开公网映射，不写公网解析（玩家经启动器接入）。</li>
 * </ul>
 *
 * <p>地址统一「填 IP 或主机名」：IP 直接写 A/AAAA；主机名写 CNAME，并且 SRV 目标改用该主机名
 * （RFC 2181 要求 SRV 目标不是别名）。
 */
public final class NodeAccess {

    public static final String MODE_DIRECT = "direct";
    public static final String MODE_MANUAL = "manual";
    public static final String MODE_FRP = "frp";
    /** 单端口入口（mc-router）：入口按域名转发，A 指向入口地址。 */
    public static final String MODE_ENTRY = "entry";
    public static final String MODE_P2P = "p2p";

    private NodeAccess() {
    }

    /** 归一化接入方式（空/未知一律回落 direct，历史文档无需迁移）。 */
    public static String normalizeMode(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase();
        return switch (value) {
            case MODE_MANUAL, MODE_FRP, MODE_ENTRY, MODE_P2P -> value;
            default -> MODE_DIRECT;
        };
    }

    public static String label(String mode) {
        return switch (normalizeMode(mode)) {
            case MODE_MANUAL -> "自定义解析地址";
            case MODE_FRP -> "FRP 入口";
            case MODE_ENTRY -> "单端口入口";
            case MODE_P2P -> "启动器打洞";
            default -> "节点直连";
        };
    }

    /** 该方式是否需要在节点上填写解析地址。 */
    public static boolean requiresHost(String mode) {
        String normalized = normalizeMode(mode);
        return MODE_MANUAL.equals(normalized) || MODE_FRP.equals(normalized);
    }

    /** 该方式是否写公网解析记录（打洞不写）。 */
    public static boolean writesDns(String mode) {
        return !MODE_P2P.equals(normalizeMode(mode));
    }

    public static boolean isIpv4(String value) {
        return value != null && value.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")
                && java.util.Arrays.stream(value.split("\\."))
                .allMatch(part -> part.length() <= 3 && Integer.parseInt(part) <= 255);
    }

    public static boolean isIpv6(String value) {
        return value != null && value.contains(":") && isInetAddress(value);
    }

    /**
     * 校验「解析地址」：IP（v4/v6）或主机名；不接受 scheme、端口、路径与空白
     * （避免把 URL 当主机名写进 DNS，也避免节点侧配置被用于其他用途）。
     */
    public static boolean validAddress(String value) {
        String host = value == null ? "" : value.trim();
        if (host.isEmpty() || host.contains("/") || host.contains("\\") || host.contains(" ")
                || host.contains("://") || host.length() > 253) {
            return false;
        }
        if (isIpv4(host)) {
            return true;
        }
        if (host.matches("^[0-9.]+$")) {
            // 纯数字点分串但不是合法 IPv4（如 999.1.1.1）：不得当成主机名放行。
            return false;
        }
        if (host.contains(":")) {
            // 含冒号的只可能是 IPv6：按字面量解析（不触发 DNS 查询），带端口/括号的一律拒绝。
            return isIpv6(host);
        }
        return host.matches("(?i)^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");
    }

    /** 解析地址对应的 DNS 记录类型：IP → A/AAAA，主机名 → CNAME。 */
    public static String recordTypeOf(String address) {
        if (isIpv4(address)) {
            return "A";
        }
        if (isIpv6(address)) {
            return "AAAA";
        }
        return "CNAME";
    }

    private static boolean isInetAddress(String value) {
        try {
            InetAddress address = InetAddress.getByName(value);
            return address instanceof java.net.Inet6Address;
        }
        catch (Exception ignored) {
            return false;
        }
    }
}
