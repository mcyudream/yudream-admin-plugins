package online.yudream.base.plugin.mcpanel.application.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板设置（单例文档 mcpanel_settings/settings）。
 * 密钥类字段（CF token）不落文档：只在 SecretStore 存在时回显 configured 标记。
 *
 * <p>注入下载源（authlib/playtime）的制品支持两种来源：上传到平台文件库（fileId，
 * 由制品上传端点签发）或外部下载地址（url）；两者至少填一个。playtime 按
 * 「插件形态（Paper 等多版本）/ mod 形态（按加载器细分）」做版本矩阵，运行时按
 * 实例的核心类型与 MC 版本挑选匹配制品（注入链路落地时消费）。
 */
public record PanelSettings(
        Authlib authlib,
        Playtime playtime,
        Modpack modpack,
        Dns dns,
        Entry entry,
        Tenancy tenancy,
        Contribution contribution,
        P2p p2p,
        CoreDownload coreDownload,
        SftpGateway sftpGateway) {

    /** authlib-injector 注入源。apiRoot 可手填；authlib-injector 插件可用时展示自动值兜底。 */
    public record Authlib(boolean enabled, String jarFileId, String jarUrl, String apiRoot, String sha256) {
    }

    /** 时长统计注入源：制品矩阵（插件/模组 × MC 版本 × 加载器）。 */
    public record Playtime(boolean enabled, List<Artifact> artifacts) {
    }

    /**
     * 注入制品条目。
     *
     * @param kind    plugin（Paper 等服务端插件，按 MC 版本区分）/ mod（按加载器细分的模组）
     * @param loaders mod 形态必填：fabric/forge/neoforge/quilt；plugin 形态为空
     * @param mcMin   适用 MC 版本下限（含），如 1.20；空 = 不限
     * @param mcMax   适用 MC 版本上限（含）；空 = 不限
     * @param fileId  平台文件库制品 ID（制品上传端点签发）
     * @param url     外部下载地址（与 fileId 二选一）
     */
    public record Artifact(String name, String kind, List<String> loaders,
                           String mcMin, String mcMax, String fileId, String url, String sha256) {
        public Artifact {
            loaders = loaders == null ? List.of() : List.copyOf(loaders);
        }
    }

    /**
     * 资源源（Modrinth / CurseForge 换源）：按列表顺序尝试、失败回退下一个。
     *
     * @param name    展示名（如「MCIM 镜像」「官方源」）
     * @param apiBase API 前缀（含版本段，如 https://api.modrinth.com/v2）；可为空 = 该源只用于文件直链
     * @param cdnBase 文件直链前缀（替换官方 CDN 主机，路径保持原样）
     */
    public record ResourceSource(String name, String apiBase, String cdnBase) {
    }

    public record Modpack(String mirror, boolean allowCurseforge,
                          List<ResourceSource> modrinthSources, List<ResourceSource> curseforgeSources) {
    }

    /**
     * 实例域名（DNS 自动解析）。
     *
     * @param mode     驱动：off / cloudflare / aliyun / dnspod
     * @param suffix   游戏域后缀（记录名 = slug.后缀）
     * @param provider 区域标识与端点：Cloudflare 填 Zone ID，阿里云/腾讯云填根域名；
     *                 apiBase 留空用各家官方端点
     */
    public record Dns(String mode, String suffix, long ttlSeconds, Provider provider) {
        public record Provider(String zone, String apiBase) {
        }
    }

    /**
     * 单端口入口（mc-router）：所有实例共用一个入口端口，入口按玩家请求的域名把连接
     * 转发到对应实例。**只有把节点接入方式设为「单端口入口」时才启用**（其余模式不推路由、
     * 不轮询 router，零外呼）。
     *
     * @param apiBase mc-router 的 REST API 基址（如 http://127.0.0.1:8080）；无鉴权，请只在内网暴露
     * @param host    入口对外地址（写 A 记录用；玩家实际连的地址）
     * @param port    入口监听端口（默认 25565；非 25565 时会额外写 SRV，玩家仍用裸域名）
     */
    public record Entry(String apiBase, String host, int port) {
    }

    public record Tenancy(boolean enabled, List<String> platformRoleIds, boolean billing) {
    }

    public record Contribution(boolean enabled, boolean reviewRequired, int maxPerUser,
                               long maxInstances, long maxCpuMillis, long maxMemoryMb) {
    }

    public record P2p(boolean enabled, long defaultRateKbps, int maxSessionsPerUser) {
    }

    /** 核心下载源：FastMirror 镜像站基址（失败自动官方兜底）。 */
    public record CoreDownload(String fastMirrorBase) {
    }

    /**
     * SFTP 单端口网关：面板 JVM 内嵌 SSH 端点，桌面客户端凭
     * mc-短别名 + 随机密码接入，节点端口不再对用户暴露。
     *
     * @param enabled        关闭时 ftp.open 维持节点直连形态
     * @param port           网关监听端口（1-65535，需与防火墙/容器映射放行一致）
     * @param advertisedHost 展示给管理员的主机名/IP；留空 = 前端按当前站点主机名展示
     */
    public record SftpGateway(boolean enabled, int port, String advertisedHost) {
    }

    /** 密钥类字段的「已配置」标记（真值只存在于 SecretStore，界面只回显是否已配置）。 */
    public record Configured(boolean modpackCfKey, boolean dnsCloudflareToken,
                             boolean dnsAliyun, boolean dnsTencent) {
    }

    /** Modrinth 默认源：MCIM 镜像优先（国内可达），官方兜底；文件下载在镜像上 302 跳其 CDN。 */
    public static List<ResourceSource> defaultModrinthSources() {
        return List.of(
                new ResourceSource("MCIM 镜像", "https://mod.mcimirror.top/modrinth/v2", "https://mod.mcimirror.top"),
                new ResourceSource("官方源", "https://api.modrinth.com/v2", "https://cdn.modrinth.com"));
    }

    /** CurseForge 默认源：MCIM 镜像 edge.forgecdn.net 路径直代，官方兜底（mediafilez 不映射，原链兜底）。 */
    public static List<ResourceSource> defaultCurseforgeSources() {
        return List.of(
                new ResourceSource("MCIM 镜像", "https://mod.mcimirror.top/curseforge/v1", "https://mod.mcimirror.top"),
                new ResourceSource("官方源", "https://api.curseforge.com/v1", "https://edge.forgecdn.net"));
    }

    public static PanelSettings defaults() {
        return new PanelSettings(
                new Authlib(false, null, "", "", ""),
                new Playtime(false, List.of()),
                new Modpack("", false, defaultModrinthSources(), defaultCurseforgeSources()),
                new Dns("off", "", 120, new Dns.Provider("", "")),
                new Entry("", "", 25565),
                new Tenancy(false, List.of(), false),
                new Contribution(false, true, 2, 3, 4000, 8192),
                new P2p(false, 2048, 2),
                new CoreDownload("https://download.fastmirror.net"),
                new SftpGateway(false, 0, ""));
    }

    public Map<String, Object> toMap(Configured configured) {
        Configured flags = configured == null ? new Configured(false, false, false, false) : configured;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("authlib", authlib == null ? Map.of() : toMapOf(authlib));
        map.put("playtime", playtime == null ? Map.of() : toMapOf(playtime));
        Map<String, Object> modpackView = new LinkedHashMap<>();
        modpackView.put("mirror", modpack == null ? "" : modpack.mirror());
        modpackView.put("allowCurseforge", modpack != null && modpack.allowCurseforge());
        // 源列表回显有效值（null → 内置默认），前端按此编辑，保存原样带回。
        modpackView.put("modrinthSources", modpack == null || modpack.modrinthSources() == null
                ? defaultModrinthSources() : modpack.modrinthSources());
        modpackView.put("curseforgeSources", modpack == null || modpack.curseforgeSources() == null
                ? defaultCurseforgeSources() : modpack.curseforgeSources());
        modpackView.put("cfApiKeyConfigured", flags.modpackCfKey());
        map.put("modpack", modpackView);
        Map<String, Object> dnsView = new LinkedHashMap<>();
        dnsView.put("mode", dns == null ? "off" : dns.mode());
        dnsView.put("suffix", dns == null ? "" : dns.suffix());
        dnsView.put("ttlSeconds", dns == null ? 120 : dns.ttlSeconds());
        Map<String, Object> provider = new LinkedHashMap<>();
        if (dns != null && dns.provider() != null) {
            provider.put("zone", dns.provider().zone());
            provider.put("apiBase", dns.provider().apiBase());
        }
        dnsView.put("provider", provider);
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("cloudflareTokenConfigured", flags.dnsCloudflareToken());
        credentials.put("aliyunConfigured", flags.dnsAliyun());
        credentials.put("tencentConfigured", flags.dnsTencent());
        dnsView.put("credentials", credentials);
        map.put("dns", dnsView);
        Map<String, Object> entryView = new LinkedHashMap<>();
        entryView.put("apiBase", entry == null ? "" : entry.apiBase());
        entryView.put("host", entry == null ? "" : entry.host());
        entryView.put("port", entry == null || entry.port() <= 0 ? 25565 : entry.port());
        map.put("entry", entryView);
        map.put("tenancy", tenancy == null ? Map.of() : toMapOf(tenancy));
        map.put("contribution", contribution == null ? Map.of() : toMapOf(contribution));
        map.put("p2p", p2p == null ? Map.of() : toMapOf(p2p));
        map.put("coreDownload", coreDownload == null
                ? Map.of("fastMirrorBase", "https://download.fastmirror.net")
                : toMapOf(coreDownload));
        map.put("sftpGateway", sftpGateway == null
                ? Map.of("enabled", false, "port", 0, "advertisedHost", "")
                : toMapOf(sftpGateway));
        return map;
    }

    private static Map<String, Object> toMapOf(Object value) {
        return online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson.mapper()
                .convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }
}
