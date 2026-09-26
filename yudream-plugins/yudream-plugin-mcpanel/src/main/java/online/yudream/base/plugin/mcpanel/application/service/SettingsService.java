package online.yudream.base.plugin.mcpanel.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeAccess;
import online.yudream.base.plugin.mcpanel.infrastructure.dns.DnsCredentials;
import online.yudream.base.plugin.mcpanel.infrastructure.dns.DnsNames;
import online.yudream.base.plugin.mcpanel.infrastructure.dns.DnsProviders;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** 设置读写（单例文档 + SecretStore 密钥引用）。 */
public class SettingsService {

    private static final String COLLECTION = "mcpanel_settings";
    private static final String DOC_ID = "settings";
    /** 整合包（CurseForge）专用密钥槽——不再与 DNS 共用。 */
    private static final String CF_KEY = "mcpanel:modpack:cf-key";

    /** 云解析凭据密钥槽（按驱动分开；secretUpdates 的键名 = 去掉前缀的短名）。 */
    private static final Map<String, String> SECRET_KEYS = Map.of(
            "modpackCfKey", CF_KEY,
            "dnsCloudflareToken", "mcpanel:dns:cloudflare-token",
            "dnsAliyunAccessKeyId", "mcpanel:dns:aliyun-ak",
            "dnsAliyunAccessKeySecret", "mcpanel:dns:aliyun-sk",
            "dnsTencentSecretId", "mcpanel:dns:tencent-sid",
            "dnsTencentSecretKey", "mcpanel:dns:tencent-skey");

    private static final List<String> ARTIFACT_KINDS = List.of("plugin", "mod");
    private static final List<String> MOD_LOADERS = List.of("fabric", "forge", "neoforge", "quilt");
    private static final String MC_VERSION_PATTERN = "\\d+\\.\\d+(\\.\\d+)?";

    private final PluginDocumentStore documents;
    private final ObjectMapper mapper;
    private final NodeSecrets secrets;
    private final Supplier<Optional<String>> authlibApiRootAuto;
    /** 设置保存成功后的回调（bootstrap 用于按新配置热启停 SFTP 网关）。 */
    private volatile Runnable onSaved;

    public SettingsService(PluginDocumentStore documents, ObjectMapper mapper, NodeSecrets secrets,
                           Supplier<Optional<String>> authlibApiRootAuto) {
        this.documents = documents;
        this.mapper = mapper;
        this.secrets = secrets;
        this.authlibApiRootAuto = authlibApiRootAuto;
    }

    public PanelSettings load() {
        return documents.findById(COLLECTION, DOC_ID)
                .map(document -> mapper.convertValue(document, PanelSettings.class))
                .orElseGet(PanelSettings::defaults);
    }

    public Map<String, Object> view() {
        Map<String, Object> view = load().toMap(configuredFlags());
        injectAuthlibAuto(view);
        return view;
    }

    /**
     * 保存设置。
     *
     * @param secretUpdates 密钥更新（键名见 {@link #SECRET_KEYS}）：缺省/null = 保持不变，
     *                      空串 = 清除该密钥
     */
    public Map<String, Object> save(PanelSettings updated, Map<String, String> secretUpdates) {
        PanelSettings merged = merge(load(), updated);
        applySecretUpdates(secretUpdates);
        validate(merged, dnsCredentials());
        documents.save(COLLECTION, DOC_ID, mapper.convertValue(merged,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                }));
        Map<String, Object> view = merged.toMap(configuredFlags());
        injectAuthlibAuto(view);
        Runnable hook = onSaved;
        if (hook != null) {
            hook.run();
        }
        return view;
    }

    /** 应用密钥更新：键名白名单外的键一律忽略（防止任意写 SecretStore）。 */
    private void applySecretUpdates(Map<String, String> secretUpdates) {
        if (secretUpdates == null || secretUpdates.isEmpty()) {
            return;
        }
        secretUpdates.forEach((name, value) -> {
            String key = SECRET_KEYS.get(name);
            if (key == null || value == null) {
                return;
            }
            if (value.isBlank()) {
                secrets.deleteRaw(key);
            } else {
                secrets.putRaw(key, value.trim());
            }
        });
    }

    /** 密钥「已配置」标记（界面只回显布尔，不读真值）。 */
    private PanelSettings.Configured configuredFlags() {
        return new PanelSettings.Configured(
                secrets.raw(SECRET_KEYS.get("modpackCfKey")).isPresent(),
                secrets.raw(SECRET_KEYS.get("dnsCloudflareToken")).isPresent(),
                secrets.raw(SECRET_KEYS.get("dnsAliyunAccessKeyId")).isPresent()
                        && secrets.raw(SECRET_KEYS.get("dnsAliyunAccessKeySecret")).isPresent(),
                secrets.raw(SECRET_KEYS.get("dnsTencentSecretId")).isPresent()
                        && secrets.raw(SECRET_KEYS.get("dnsTencentSecretKey")).isPresent());
    }

    /** 当前生效的云解析凭据（缺失字段为空串）。 */
    public DnsCredentials dnsCredentials() {
        return new DnsCredentials(
                secret("dnsCloudflareToken").orElse(""),
                secret("dnsAliyunAccessKeyId").orElse(""),
                secret("dnsAliyunAccessKeySecret").orElse(""),
                secret("dnsTencentSecretId").orElse(""),
                secret("dnsTencentSecretKey").orElse(""));
    }

    private Optional<String> secret(String name) {
        String key = SECRET_KEYS.get(name);
        return key == null ? Optional.empty() : secrets.raw(key);
    }

    /** 设置保存成功后的回调（网关热启停等；回调异常不阻断保存结果）。 */
    public void setOnSaved(Runnable callback) {
        this.onSaved = callback;
    }

    /** SFTP 网关当前生效配置（历史文档缺字段时回落默认关闭）。 */
    public PanelSettings.SftpGateway sftpGatewayView() {
        PanelSettings.SftpGateway gateway = load().sftpGateway();
        return gateway == null ? PanelSettings.defaults().sftpGateway() : gateway;
    }

    public Optional<String> cfApiKey() {
        return secrets.raw(CF_KEY);
    }

    /** 把 authlib-injector 插件联动得到的验证服 API 根注入视图（不覆盖手填值）。 */
    private void injectAuthlibAuto(Map<String, Object> view) {
        Object authlib = view.get("authlib");
        if (!(authlib instanceof Map<?, ?> authlibMap)) {
            return;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        authlibMap.forEach((key, value) -> copy.put(String.valueOf(key), value));
        Optional<String> auto = authlibApiRootAuto == null ? Optional.empty() : authlibApiRootAuto.get();
        copy.put("apiRootAuto", auto.orElse(""));
        copy.put("apiRootAutoAvailable", auto.isPresent());
        view.put("authlib", copy);
    }

    private PanelSettings merge(PanelSettings current, PanelSettings updated) {
        if (updated == null) {
            return current;
        }
        return updated;
    }

    private void validate(PanelSettings settings, DnsCredentials credentials) {
        validateDns(settings.dns(), credentials);
        validateEntry(settings.entry());
        if (settings.contribution() != null && settings.contribution().enabled()) {
            if (settings.contribution().maxPerUser() < 1 || settings.contribution().maxPerUser() > 10
                    || settings.contribution().maxInstances() < 1 || settings.contribution().maxCpuMillis() < 100
                    || settings.contribution().maxMemoryMb() < 256) {
                throw McpanelBusinessException.invalid("节点贡献限额不合法");
            }
        }
        validateAuthlib(settings.authlib());
        validatePlaytime(settings.playtime());
        validateSftpGateway(settings.sftpGateway());
    }

    /**
     * 域名自动解析配置校验：驱动白名单、后缀格式、区域标识，以及「后缀落在根域名之内」
     * 与凭据齐备——这些错误若拖到实例创建时才暴露，用户会拿不到域名却不知为何。
     */
    static void validateDns(PanelSettings.Dns dns, DnsCredentials credentials) {
        if (dns == null || "off".equals(dns.mode())) {
            return;
        }
        String mode = dns.mode() == null ? "" : dns.mode().trim().toLowerCase();
        if (!DnsProviders.supported(mode)) {
            throw McpanelBusinessException.invalid("DNS 驱动仅支持 off / " + String.join(" / ", DnsProviders.TYPES));
        }
        if (dns.suffix() == null || !dns.suffix().matches("([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}")) {
            throw McpanelBusinessException.invalid("DNS 后缀必须是合法域名（如 mc.example.com）");
        }
        String zone = dns.provider() == null ? "" : dns.provider().zone();
        if (zone == null || zone.isBlank()) {
            throw McpanelBusinessException.invalid(DnsProviders.label(mode) + " 驱动需要填写 "
                    + DnsProviders.zoneLabel(mode));
        }
        if (!"cloudflare".equals(mode) && !DnsNames.covers(zone, dns.suffix())) {
            throw McpanelBusinessException.invalid("游戏域后缀 " + dns.suffix() + " 不在根域名 " + zone
                    + " 之内；请填写该域名所在云账号的根域名（如 example.com）");
        }
        String missing = DnsProviders.missingCredentials(mode, credentials);
        if (missing != null) {
            throw McpanelBusinessException.invalid(DnsProviders.label(mode) + " 驱动缺少凭据：" + missing
                    + "（面板设置 → 实例域名）");
        }
    }

    /**
     * 单端口入口（mc-router）配置校验：字段各自合法即可（允许先填 API 后填入口地址，
     * 未填齐时域名页/路由状态会明确说明缺什么，而不是静默失败）。
     */
    static void validateEntry(PanelSettings.Entry entry) {
        if (entry == null) {
            return;
        }
        String apiBase = entry.apiBase() == null ? "" : entry.apiBase().trim();
        if (!apiBase.isEmpty()) {
            if (!apiBase.matches("(?i)^https?://[^\\s/]+.*$")) {
                throw McpanelBusinessException.invalid("入口 API 地址必须以 http:// 或 https:// 开头（如 http://127.0.0.1:8080）");
            }
        }
        String host = entry.host() == null ? "" : entry.host().trim();
        if (!host.isEmpty() && !NodeAccess.validAddress(host)) {
            throw McpanelBusinessException.invalid("入口对外地址只能是 IP（IPv4/IPv6）或主机名，不含协议、端口与路径");
        }
        if (entry.port() != 0 && (entry.port() < 1 || entry.port() > 65535)) {
            throw McpanelBusinessException.invalid("入口端口必须是 1-65535（默认 25565）");
        }
    }

    /** 网关配置校验：端口必填合法；广告地址只允许主机名/IP（协议与端口各自有专属字段）。 */
    static void validateSftpGateway(PanelSettings.SftpGateway gateway) {
        if (gateway == null || !gateway.enabled()) {
            return;
        }
        if (gateway.port() < 1 || gateway.port() > 65535) {
            throw McpanelBusinessException.invalid("SFTP 网关端口必须是 1-65535");
        }
        String host = gateway.advertisedHost();
        if (host != null && !host.isBlank() && !host.trim().matches("[A-Za-z0-9_.-]{1,255}")) {
            throw McpanelBusinessException.invalid("SFTP 网关广告地址只能是主机名或 IP，不含协议与端口");
        }
    }

    private void validateAuthlib(PanelSettings.Authlib authlib) {
        if (authlib == null || !authlib.enabled()) {
            return;
        }
        boolean hasFile = authlib.jarFileId() != null && !authlib.jarFileId().isBlank();
        boolean hasUrl = authlib.jarUrl() != null && !authlib.jarUrl().isBlank();
        if (!hasFile && !hasUrl) {
            throw McpanelBusinessException.invalid("authlib 注入源需要上传 jar 或填写外部 URL");
        }
        if (hasFile && !authlib.jarFileId().startsWith(ArtifactStoreService.KEY_PREFIX)) {
            throw McpanelBusinessException.invalid("authlib jarFileId 必须是面板制品库文件（mcpanel/artifacts/ 前缀）");
        }
    }

    private void validatePlaytime(PanelSettings.Playtime playtime) {
        if (playtime == null || !playtime.enabled()) {
            return;
        }
        if (playtime.artifacts() == null || playtime.artifacts().isEmpty()) {
            throw McpanelBusinessException.invalid("时长统计启用后至少需要一个制品");
        }
        for (PanelSettings.Artifact artifact : playtime.artifacts()) {
            if (artifact.name() == null || artifact.name().isBlank()) {
                throw McpanelBusinessException.invalid("制品名称不能为空");
            }
            if (!ARTIFACT_KINDS.contains(artifact.kind())) {
                throw McpanelBusinessException.invalid("制品类型仅支持 plugin / mod");
            }
            if ("mod".equals(artifact.kind())) {
                if (artifact.loaders().isEmpty()) {
                    throw McpanelBusinessException.invalid("mod 制品必须指定加载器");
                }
                for (String loader : artifact.loaders()) {
                    if (!MOD_LOADERS.contains(loader)) {
                        throw McpanelBusinessException.invalid("不支持的加载器：" + loader);
                    }
                }
            }
            boolean hasFile = artifact.fileId() != null && !artifact.fileId().isBlank();
            boolean hasUrl = artifact.url() != null && !artifact.url().isBlank();
            if (!hasFile && !hasUrl) {
                throw McpanelBusinessException.invalid("制品「" + artifact.name() + "」需要上传文件或填写外部 URL");
            }
            if (hasFile && !artifact.fileId().startsWith(ArtifactStoreService.KEY_PREFIX)) {
                throw McpanelBusinessException.invalid("制品 fileId 必须是面板制品库文件（mcpanel/artifacts/ 前缀）");
            }
            validateMcVersion(artifact.mcMin(), "mcMin");
            validateMcVersion(artifact.mcMax(), "mcMax");
        }
    }

    private void validateMcVersion(String value, String field) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!value.matches(MC_VERSION_PATTERN)) {
            throw McpanelBusinessException.invalid(field + " 必须是 MC 版本号（如 1.20.1）");
        }
    }
}
