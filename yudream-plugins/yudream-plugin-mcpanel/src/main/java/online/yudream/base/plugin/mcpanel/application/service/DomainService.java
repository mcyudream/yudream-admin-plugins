package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.infrastructure.dns.DnsCallException;
import online.yudream.base.plugin.mcpanel.infrastructure.dns.DnsCredentials;
import online.yudream.base.plugin.mcpanel.infrastructure.dns.DnsProvider;
import online.yudream.base.plugin.mcpanel.infrastructure.dns.DnsProviders;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 实例域名自动解析（A + SRV 随实例生命周期增删）。
 *
 * <p>业务语义在这一层，协议差异在 {@link DnsProvider} 实现里（Cloudflare / 阿里云 / 腾讯云）：
 * - 记录名 = <code>{slug}.{suffix}</code>；SRV = <code>_minecraft._tcp.{fqdn}</code>，
 *   值为 <code>0 5 {port} {fqdn}</code>（让玩家免端口直连，仅 Java 版有意义）；
 * - slug 由实例名推导（冲突自动加序号），一旦分配即随实例持久化，实例改名不影响已发域名；
 * - TTL 取「面板配置」与「驱动下限」的较大值（阿里云/腾讯云免费档 600 秒起，低于会被直接拒绝）；
 * - 驱动未启用/凭据缺失时整体降级：{@link #enabled()} 为 false，界面隐藏入口而不是报错。
 */
public class DomainService {

    /** SRV 记录的优先级/权重（面板不暴露，够用即可）。 */
    private static final int SRV_PRIORITY = 0;
    private static final int SRV_WEIGHT = 5;
    /** slug 推导上限：超过则截断（DNS 标签 63 字符内，留出后缀余量）。 */
    private static final int MAX_SLUG_LENGTH = 40;

    private final SettingsService settings;
    private final InstancePort instances;
    private final McpanelInstanceAppService.AuditRecorder audit;
    private final ProviderFactory providers;

    /** 实例侧读写通道（bootstrap 装配；函数式隔离便于测试）。 */
    public interface InstancePort {

        List<McpanelInstance> all();

        /** 持久化域名分配状态（slug + 是否自动分配）。 */
        void setDomain(String instanceId, String slug, boolean enabled);
    }

    /**
     * 解析目标：由「节点接入方式」决定记录怎么写。
     *
     * @param mode        接入方式（direct/manual/frp/entry/p2p）
     * @param modeLabel   接入方式中文名
     * @param recordType  A / AAAA / CNAME；{@code null} = 该方式不写公网解析（打洞）
     * @param recordValue 记录值（IP 或主机名）
     * @param srvTarget   SRV 目标域；null/空 = 用 fqdn 自身（CNAME 时必须指真实主机）
     * @param srvPort     SRV 端口：null = 用实例端口，&gt;0 = 用该端口（单端口入口），&lt;=0 = 不写 SRV
     * @param blockReason 不可分配时的说明（打洞/未配置地址）
     */
    public record Target(String mode, String modeLabel, String recordType, String recordValue,
                         String srvTarget, Integer srvPort, String blockReason) {

        /** 常规目标（SRV 端口取实例端口）。 */
        public Target(String mode, String modeLabel, String recordType, String recordValue,
                      String srvTarget, String blockReason) {
            this(mode, modeLabel, recordType, recordValue, srvTarget, null, blockReason);
        }

        public boolean assignable() {
            return blockReason == null && recordType != null && !recordType.isBlank()
                    && recordValue != null && !recordValue.isBlank();
        }

        public static Target blocked(String mode, String modeLabel, String reason) {
            return new Target(mode, modeLabel, null, "", null, null, reason);
        }
    }

    /** 驱动实例化出口：默认走真实 HTTP；测试注入内存驱动即可覆盖分配/释放/校验全链路。 */
    public interface ProviderFactory {
        DnsProvider create(String driver, String zone, String apiBase, DnsCredentials credentials);
    }

    public DomainService(SettingsService settings, InstancePort instances,
                         McpanelInstanceAppService.AuditRecorder audit) {
        this(settings, instances, audit, defaultFactory());
    }

    DomainService(SettingsService settings, InstancePort instances,
                  McpanelInstanceAppService.AuditRecorder audit, ProviderFactory providers) {
        this.settings = settings;
        this.instances = instances;
        this.audit = audit;
        this.providers = providers;
    }

    private static ProviderFactory defaultFactory() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (driver, zone, apiBase, credentials) ->
                DnsProviders.create(driver, zone, apiBase, credentials, http, McpanelJson.mapper());
    }

    // ---------- 配置视图 ----------

    public PanelSettings.Dns dns() {
        return settings.load().dns();
    }

    /** 驱动标识（off = 未启用）。 */
    public String driver() {
        PanelSettings.Dns dns = dns();
        if (dns == null || dns.mode() == null) {
            return "off";
        }
        String mode = dns.mode().trim().toLowerCase(Locale.ROOT);
        return "off".equals(mode) ? "off" : mode;
    }

    public boolean enabled() {
        return !"off".equals(driver()) && DnsProviders.supported(driver());
    }

    public String suffix() {
        PanelSettings.Dns dns = dns();
        return dns == null || dns.suffix() == null ? "" : dns.suffix().trim();
    }

    /** 驱动未启用/凭据不全的原因（可读中文；可用时返回 null）。 */
    public String disabledReason() {
        if (!enabled()) {
            return "面板未启用域名自动解析（面板设置 → 实例域名）";
        }
        String zone = dns().provider() == null ? "" : dns().provider().zone();
        if (zone == null || zone.isBlank()) {
            return DnsProviders.label(driver()) + " 驱动未填写 " + DnsProviders.zoneLabel(driver());
        }
        String missing = DnsProviders.missingCredentials(driver(), settings.dnsCredentials());
        return missing == null ? null : DnsProviders.label(driver()) + " 凭据未配置：" + missing;
    }

    /** 生效 TTL：不低于驱动下限。 */
    public int effectiveTtl() {
        DnsProvider provider = providerOrNull();
        return effectiveTtl(provider);
    }

    private int effectiveTtl(DnsProvider provider) {
        PanelSettings.Dns dns = dns();
        int ttl = (int) Math.max(60, dns == null ? 120 : dns.ttlSeconds());
        return provider == null ? ttl : Math.max(ttl, provider.minTtlSeconds());
    }

    /** 面板配置里的 TTL（未考虑驱动下限；驱动不可用时用于展示）。 */
    private int configuredTtl() {
        PanelSettings.Dns dns = dns();
        return (int) Math.max(60, dns == null ? 120 : dns.ttlSeconds());
    }

    private DnsProvider providerOrNull() {
        if (!enabled()) {
            return null;
        }
        try {
            return buildProvider();
        }
        catch (DnsCallException error) {
            return null;
        }
    }

    private DnsProvider buildProvider() {
        PanelSettings.Dns dns = dns();
        PanelSettings.Dns.Provider config = dns == null ? null : dns.provider();
        return providers.create(driver(), config == null ? "" : config.zone(),
                config == null ? "" : config.apiBase(), settings.dnsCredentials());
    }

    private DnsProvider requireProvider() {
        String reason = disabledReason();
        if (reason != null) {
            throw new McpanelBusinessException("domain.disabled", 409, reason);
        }
        try {
            return buildProvider();
        }
        catch (DnsCallException error) {
            throw new McpanelBusinessException("domain.disabled", 409, error.getMessage());
        }
    }

    // ---------- 记录名 ----------

    /** 完整记录名：已分配用持久化 slug，否则按实例名推导（预览用）。 */
    public String fqdn(McpanelInstance instance) {
        String slug = instance.domainSlug();
        if (slug == null || slug.isBlank()) {
            slug = suggestSlug(instance);
        }
        return slug + "." + suffix();
    }

    public String srvName(McpanelInstance instance) {
        return "_minecraft._tcp." + fqdn(instance);
    }

    /**
     * 反查：玩家手上的连接地址对应哪台实例（启动器 P2P 需要「服务器 → 实例」的映射）。
     *
     * <p>只认**已分配域名**的实例（持久化过 slug 且 domainEnabled），不拿推导出的预览名参与匹配——
     * 预览名随时会因实例改名而变，不是真实记录。地址大小写不敏感，端口、末尾点与 IPv6 方括号一并忽略。
     *
     * @return 匹配到的实例；未配置后缀或无人匹配时 empty
     */
    public Optional<McpanelInstance> instanceForHost(String address) {
        String host = normalizeHost(address);
        String suffix = suffix();
        if (host.isEmpty() || suffix.isEmpty() || instances == null) {
            return Optional.empty();
        }
        String tail = "." + suffix;
        for (McpanelInstance instance : instances.all()) {
            String slug = instance.domainSlug();
            if (!instance.domainEnabled() || slug == null || slug.isBlank()) {
                continue;
            }
            if (normalizeHost(slug + tail).equals(host)) {
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    /** 主机名归一化：去端口、去 IPv6 方括号、去末尾点、统一小写。 */
    static String normalizeHost(String address) {
        if (address == null) {
            return "";
        }
        String value = address.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            value = end < 0 ? value.substring(1) : value.substring(1, end);
        }
        else {
            int colon = value.indexOf(':');
            if (colon >= 0) {
                value = value.substring(0, colon);
            }
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 实例首个 TCP 映射端口（SRV 目标端口；无则 0）。 */
    public int gamePort(McpanelInstance instance) {
        return instance.ports().stream()
                .filter(mapping -> "tcp".equals(mapping.proto()))
                .mapToInt(McpanelInstance.PortMapping::hostPort)
                .findFirst()
                .orElse(0);
    }

    /**
     * 由实例名推导 slug：只保留小写字母/数字/连字符（DNS 标签规则）。
     * 与其他实例已占用的 slug 冲突时依次尝试 -2、-3…，仍冲突则拼上实例短 ID 保底唯一。
     */
    public String suggestSlug(McpanelInstance instance) {
        String base = sanitize(instance.name());
        if (base.isBlank()) {
            base = "server";
        }
        if (base.length() > MAX_SLUG_LENGTH) {
            base = base.substring(0, MAX_SLUG_LENGTH).replaceAll("-+$", "");
        }
        List<String> taken = new ArrayList<>();
        if (instances != null) {
            for (McpanelInstance other : instances.all()) {
                if (other.id() != null && other.id().equals(instance.id())) {
                    continue;
                }
                String slug = other.domainSlug();
                if (slug != null && !slug.isBlank()) {
                    taken.add(slug.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!taken.contains(base)) {
            return base;
        }
        for (int index = 2; index <= 9; index++) {
            String candidate = base + "-" + index;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        String shortId = instance.id() == null ? "" : instance.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (shortId.length() > 6) {
            shortId = shortId.substring(shortId.length() - 6);
        }
        return base + "-" + shortId;
    }

    static String sanitize(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        value = value.replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+", "").replaceAll("-+$", "");
        return value.replaceAll("-{2,}", "-");
    }

    // ---------- 分配 / 释放 / 校验 ----------

    /**
     * 分配（或重新同步）域名：按节点接入方式写入记录（直连/自填 IP → A/AAAA，自填主机名 → CNAME），
     * Java 版再写 SRV 让玩家免端口直连。幂等：同名同类型记录存在即更新，可反复调用。
     */
    public Map<String, Object> assign(String actor, McpanelInstance instance, Target target, boolean srv) {
        DnsProvider provider = requireProvider();
        if (!target.assignable()) {
            throw McpanelBusinessException.invalid(target.blockReason() == null
                    ? "当前节点的接入方式无法写入公网解析记录" : target.blockReason());
        }
        // SRV 端口：默认取实例端口；单端口入口模式下由目标覆盖（入口端口 25565 时无需 SRV）。
        int instancePort = gamePort(instance);
        int port = target.srvPort() == null ? instancePort : target.srvPort();
        boolean withSrv = srv && port > 0;
        String slug = instance.domainSlug();
        if (slug == null || slug.isBlank()) {
            slug = suggestSlug(instance);
        }
        String fqdn = slug + "." + suffix();
        int ttl = effectiveTtl(provider);
        String srvTarget = target.srvTarget() == null || target.srvTarget().isBlank()
                ? fqdn : target.srvTarget();
        try {
            provider.upsert(fqdn, target.recordType(), target.recordValue(), ttl);
            if (withSrv) {
                provider.upsert("_minecraft._tcp." + fqdn, "SRV",
                        SRV_PRIORITY + " " + SRV_WEIGHT + " " + port + " " + srvTarget, ttl);
            }
        }
        catch (DnsCallException error) {
            throw new McpanelBusinessException("domain.provider-error", 502, error.getMessage());
        }
        if (instances != null) {
            instances.setDomain(instance.id(), slug, true);
        }
        record(actor, "instance.domain.assign", instance,
                fqdn + " " + target.recordType() + " → " + target.recordValue()
                        + "（" + target.modeLabel() + (withSrv ? "，含 SRV :" + port + "）" : "，仅地址记录）"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slug", slug);
        result.put("fqdn", fqdn);
        result.put("address", target.recordValue());
        result.put("recordType", target.recordType());
        result.put("accessMode", target.mode());
        result.put("accessLabel", target.modeLabel());
        result.put("srv", withSrv);
        result.put("srvName", withSrv ? "_minecraft._tcp." + fqdn : "");
        result.put("srvTarget", withSrv ? srvTarget : "");
        result.put("port", port);
        result.put("ttlSeconds", ttl);
        result.put("driver", provider.type());
        result.put("driverLabel", DnsProviders.label(provider.type()));
        return result;
    }

    /** 释放域名：删除地址记录（A/AAAA/CNAME）与 SRV，并关闭分配开关。 */
    public Map<String, Object> release(String actor, McpanelInstance instance) {
        DnsProvider provider = requireProvider();
        String slug = instance.domainSlug();
        Map<String, Object> result = new LinkedHashMap<>();
        if (slug != null && !slug.isBlank()) {
            String fqdn = slug + "." + suffix();
            try {
                provider.delete("_minecraft._tcp." + fqdn, "SRV");
                // 接入方式可能变过：三种地址记录类型都清一遍（不存在则忽略）。
                provider.delete(fqdn, "A");
                provider.delete(fqdn, "AAAA");
                provider.delete(fqdn, "CNAME");
            }
            catch (DnsCallException error) {
                throw new McpanelBusinessException("domain.provider-error", 502, error.getMessage());
            }
            result.put("fqdn", fqdn);
        }
        if (instances != null) {
            instances.setDomain(instance.id(), slug == null ? "" : slug, false);
        }
        record(actor, "instance.domain.release", instance, String.valueOf(result.getOrDefault("fqdn", "")));
        result.put("released", true);
        return result;
    }

    /** 本地状态视图（不发外部请求）：实例域名卡展示当前分配状态与预期解析目标。 */
    public Map<String, Object> view(McpanelInstance instance) {
        return view(instance, null);
    }

    /**
     * 本地状态视图：带 {@code target}（节点接入方式解析结果）时同时给出预期记录值与阻塞原因，
     * 让界面在分配前就能说明「会写成什么、为什么写不了」。
     */
    public Map<String, Object> view(McpanelInstance instance, Target target) {
        Map<String, Object> result = new LinkedHashMap<>();
        String reason = disabledReason();
        String slug = instance.domainSlug() == null ? "" : instance.domainSlug();
        boolean assigned = instance.domainEnabled() && !slug.isBlank();
        int instancePort = gamePort(instance);
        // 对外端口：入口模式下是入口端口（25565 时地址不带端口），其余是实例端口。
        int port = target != null && target.srvPort() != null ? target.srvPort() : instancePort;
        boolean showPort = port > 0 && port != 25565;
        result.put("driver", driver());
        result.put("driverLabel", DnsProviders.label(driver()));
        result.put("available", reason == null);
        result.put("reason", reason == null ? "" : reason);
        result.put("suffix", suffix());
        result.put("zone", dns() != null && dns().provider() != null ? dns().provider().zone() : "");
        result.put("zoneLabel", DnsProviders.zoneLabel(driver()));
        result.put("ttlSeconds", enabled() ? effectiveTtl() : configuredTtl());
        result.put("assigned", assigned);
        result.put("slug", slug);
        result.put("suggestedSlug", enabled() ? suggestSlug(instance) : "");
        result.put("fqdn", enabled() && !suffix().isBlank() ? fqdn(instance) : "");
        result.put("srvName", enabled() && !suffix().isBlank() ? srvName(instance) : "");
        result.put("port", port);
        result.put("instancePort", instancePort);
        result.put("connectAddress", assigned && !suffix().isBlank()
                ? slug + "." + suffix() + (showPort ? ":" + port : "")
                : "");
        if (target != null) {
            result.put("accessMode", target.mode());
            result.put("accessLabel", target.modeLabel());
            result.put("recordType", target.recordType() == null ? "" : target.recordType());
            result.put("recordValue", target.recordValue() == null ? "" : target.recordValue());
            result.put("srvTarget", target.srvTarget() == null ? "" : target.srvTarget());
            result.put("blockReason", target.blockReason() == null ? "" : target.blockReason());
        }
        return result;
    }

    /** 实时校验：回读云商记录与预期值比对（排障用）。 */
    public Map<String, Object> verify(McpanelInstance instance, Target target, boolean srv) {
        DnsProvider provider = requireProvider();
        if (!target.assignable()) {
            throw McpanelBusinessException.invalid(target.blockReason() == null
                    ? "当前节点的接入方式无法写入公网解析记录" : target.blockReason());
        }
        String fqdn = fqdn(instance);
        int port = target.srvPort() == null ? gamePort(instance) : target.srvPort();
        String srvTarget = target.srvTarget() == null || target.srvTarget().isBlank()
                ? fqdn : target.srvTarget();
        String expectedSrv = SRV_PRIORITY + " " + SRV_WEIGHT + " " + port + " " + srvTarget;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqdn", fqdn);
        result.put("srvName", "_minecraft._tcp." + fqdn);
        result.put("recordType", target.recordType());
        result.put("expectedAddress", target.recordValue());
        result.put("expectedSrv", expectedSrv);
        result.put("accessMode", target.mode());
        result.put("accessLabel", target.modeLabel());
        try {
            List<String> addresses = provider.values(fqdn, target.recordType());
            List<String> srvValues = provider.values("_minecraft._tcp." + fqdn, "SRV");
            boolean addressOk = addresses.contains(target.recordValue());
            boolean srvOk = !srv || srvValues.contains(expectedSrv);
            result.put("aValues", addresses);
            result.put("srvValues", srvValues);
            result.put("aOk", addressOk);
            result.put("srvOk", srvOk);
            result.put("ok", addressOk && srvOk);
        }
        catch (DnsCallException error) {
            throw new McpanelBusinessException("domain.provider-error", 502, error.getMessage());
        }
        return result;
    }

    /** 实例删除前的尽力释放：失败不阻断删除（记录可由管理员手工清理）。 */
    public void releaseQuietly(String actor, McpanelInstance instance) {
        if (instance == null || instance.domainSlug() == null || instance.domainSlug().isBlank()) {
            return;
        }
        if (!enabled()) {
            return;
        }
        try {
            release(actor, instance);
        }
        catch (RuntimeException ignored) {
            // 尽力而为：域名记录清理失败不影响实例删除。
        }
    }

    private void record(String actor, String action, McpanelInstance instance, String detail) {
        if (audit != null) {
            audit.record(actor, action, "instance", instance.id(),
                    instance.name() + "：" + detail, instance.tenantId());
        }
    }
}
