package online.yudream.base.plugin.mcpanel.domain.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 节点接入端点与 TLS 策略（SSRF 边界的唯一裁决点）：
 * - endpoint 必须在创建/编辑时由管理员给出，固定 wss://（scheme://host[:port]，禁止携带路径），
 *   面板拨号时追加 /control；任何 enroll/bootstrap 上报都不能改写；
 * - TLS 一律 PKIX/hostname；tlsMode=pinned 时使用 64 位十六进制 SHA-256 证书指纹（自签场景），
 *   指纹可留空——未注册节点在 enroll 时以节点上报指纹自动登记（TOFU），已注册节点不得清空；
 * - localDevelopment=true 仅放宽目标地址允许 loopback（默认禁止拨向面板自身环境），绝不放宽 ws 明文。
 */
public final class NodeEndpointPolicy {

    public static final String TLS_PKIX = "pkix";
    public static final String TLS_PINNED = "pinned";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "::1");

    private NodeEndpointPolicy() {
    }

    public record NormalizedEndpoint(String endpoint, String tlsMode, String pinSha256) {
    }

    public static NormalizedEndpoint validate(String rawEndpoint, String rawTlsMode,
                                              String rawPinSha256, boolean localDevelopment) {
        if (rawEndpoint == null || rawEndpoint.isBlank()) {
            throw McpanelBusinessException.invalid("必须填写节点控制端点（wss://host:port）");
        }
        String endpoint = rawEndpoint.trim();
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException error) {
            throw McpanelBusinessException.invalid("控制端点不是合法 URI");
        }
        // authority-only：拒绝 userinfo / query / fragment / 越界端口（防泄漏与错路）。
        if (uri.getUserInfo() != null) {
            throw McpanelBusinessException.invalid("控制端点不允许携带 userinfo");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw McpanelBusinessException.invalid("控制端点只允许 scheme://host[:port]，不得携带 ?query 或 #fragment");
        }
        int port = uri.getPort();
        if (port < -1 || port == 0 || port > 65535) {
            throw McpanelBusinessException.invalid("控制端点端口必须在 1-65535");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"wss".equals(scheme)) {
            throw McpanelBusinessException.invalid("控制端点必须使用 wss://（明文 ws 一律不允许，本地测试请用自签证书 + pinned）");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isBlank()) {
            throw McpanelBusinessException.invalid("控制端点缺少主机名");
        }
        if (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
            throw McpanelBusinessException.invalid("控制端点只允许 scheme://host[:port]，路径 /control 由面板自动拼接");
        }
        boolean loopback = LOOPBACK_HOSTS.contains(host) || host.startsWith("127.") || "[::1]".equals(host);
        if (loopback && !localDevelopment) {
            throw McpanelBusinessException.invalid("loopback 端点必须显式开启 localDevelopment 才允许");
        }
        if (isLinkLocalOrMetadata(host)) {
            throw McpanelBusinessException.invalid("端点不允许指向 link-local 或云 metadata 地址");
        }
        String tlsMode = rawTlsMode == null || rawTlsMode.isBlank() ? TLS_PKIX : rawTlsMode.trim().toLowerCase(Locale.ROOT);
        if (!TLS_PKIX.equals(tlsMode) && !TLS_PINNED.equals(tlsMode)) {
            throw McpanelBusinessException.invalid("tlsMode 仅支持 pkix 或 pinned");
        }
        String pin = rawPinSha256 == null ? "" : rawPinSha256.trim().toLowerCase(Locale.ROOT);
        if (TLS_PINNED.equals(tlsMode)) {
            // 空 pin 允许：未注册节点在完成注册（enroll）时以节点上报指纹自动登记
            // （TOFU，信任锚为一次性 enrollToken）；已注册节点由 McpanelNodeAppService
            // 的 update 护栏拒绝清空。非空时仍必须是完整指纹。
            if (!pin.isEmpty() && !pin.matches("[0-9a-f]{64}")) {
                throw McpanelBusinessException.invalid("证书指纹必须为 64 位十六进制 SHA-256，或留空由节点注册时自动登记");
            }
        } else if (!pin.isEmpty()) {
            throw McpanelBusinessException.invalid("pkix 模式不接受证书指纹，请改用 tlsMode=pinned");
        }
        return new NormalizedEndpoint(endpoint, tlsMode, TLS_PINNED.equals(tlsMode) ? pin : null);
    }

    /** 面板拨号地址：endpoint + /control（端点策略已禁止携带路径）。 */
    public static URI controlUri(String endpoint) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return URI.create(base + "/control");
    }

    /** link-local（169.254/fe80::）与云 metadata 地址在任何模式下都拒绝。 */
    private static boolean isLinkLocalOrMetadata(String host) {
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        return bare.startsWith("169.254.")
                || bare.startsWith("fe80:")
                || bare.startsWith("FE80:".toLowerCase(Locale.ROOT))
                || bare.endsWith(".169.254.169.254")
                || "169.254.169.254".equals(bare)
                || "metadata".equals(bare)
                || "metadata.google.internal".equals(bare);
    }

    /**
     * 拨号前目标校验：解析 endpoint 主机的全部 A/AAAA 记录并逐一定类。
     * 默认禁止 loopback/link-local/unspecified/multicast；localDevelopment 仅放宽 loopback。
     * 解析失败视为目标不可用（调用方按退避重试）。
     */
    public static void validateResolvedTargets(String endpoint, boolean localDevelopment) {
        URI control = controlUri(endpoint);
        String host = control.getHost() == null ? "" : control.getHost();
        java.net.InetAddress[] addresses;
        try {
            addresses = java.net.InetAddress.getAllByName(host);
        } catch (Exception error) {
            throw McpanelBusinessException.invalid("节点地址解析失败");
        }
        for (java.net.InetAddress address : addresses) {
            if (!isAllowedTarget(address, localDevelopment)) {
                throw McpanelBusinessException.invalid("节点解析地址不被允许");
            }
        }
    }

    private static boolean isAllowedTarget(java.net.InetAddress address, boolean localDevelopment) {
        java.net.InetAddress candidate = unwrapIpMapped(address);
        if (candidate.isAnyLocalAddress() || candidate.isMulticastAddress()) {
            return false;
        }
        if (candidate.isLoopbackAddress()) {
            return localDevelopment;
        }
        return !candidate.isLinkLocalAddress();
    }

    /** ::ffff:x.y.z.w 一律按 IPv4 规则定类（Inet6Address 判定 API 为包私有，这里按字节实现）。 */
    private static java.net.InetAddress unwrapIpMapped(java.net.InetAddress address) {
        if (!(address instanceof java.net.Inet6Address)) {
            return address;
        }
        byte[] octets = address.getAddress();
        boolean v4Mapped = octets.length == 16;
        for (int i = 0; v4Mapped && i < 10; i++) {
            if (octets[i] != 0) {
                v4Mapped = false;
            }
        }
        if (v4Mapped && octets[10] == (byte) 0xff && octets[11] == (byte) 0xff) {
            try {
                return java.net.InetAddress.getByAddress(
                        new byte[]{octets[12], octets[13], octets[14], octets[15]});
            } catch (Exception ignored) {
                return address;
            }
        }
        return address;
    }
}
