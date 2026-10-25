package online.yudream.base.plugin.union.application.service;

import online.yudream.base.plugin.union.api.UnionJoinEvidenceProvider;
import online.yudream.base.plugin.union.domain.aggregate.UnionPeer;
import online.yudream.base.plugin.union.infrastructure.UnionCryptoService;
import online.yudream.base.plugin.union.infrastructure.repository.UnionRepository;
import online.yudream.base.plugin.spi.core.PluginContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本站发现文档（协议 §3.1）：apiRoot/textureBaseUrl 从宿主站点配置推导
 * （app.web-url 优先、app.base-url 兜底，与 authlib-injector 同规则）。
 */
public class UnionDiscoveryService {

    public static final String UNION_VERSION = "1";
    public static final String UNION_API_PREFIX = "/api/plugins/yudream-union";
    public static final String AUTHLIB_API_LOCATION = "/api/plugins/authlib-injector";
    public static final String SKIN_TEXTURE_LOCATION = "/api/plugins/yudream-skin/textures";
    /** 联合名前缀规则：小写英数下划线 ≤4，预留「前缀_16位名_冗余」尽量贴近 MC 16 字符上限。 */
    public static final String FEATURE_NAME_NAMESPACES = "name-namespaces";
    public static final java.util.regex.Pattern NAME_PREFIX_PATTERN = java.util.regex.Pattern.compile("[a-z0-9_]{1,4}");

    private final PluginContext context;
    private final UnionRepository repository;
    private final UnionCryptoService cryptoService;

    public UnionDiscoveryService(PluginContext context, UnionRepository repository, UnionCryptoService cryptoService) {
        this.context = context;
        this.repository = repository;
        this.cryptoService = cryptoService;
    }

    public Map<String, Object> buildMeta() {
        String origin = configuredOrigin().orElse("");
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("code", siteCode().orElse(""));
        site.put("name", siteName().orElse(""));
        site.put("origin", origin);
        String namePrefix = namePrefix().orElse("");
        if (!namePrefix.isEmpty()) {
            site.put("namePrefix", namePrefix);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("unionVersion", UNION_VERSION);
        meta.put("site", site);
        configuredOrigin().ifPresent(originValue -> meta.put("endpoints", Map.of("apiRoot", originValue + UNION_API_PREFIX)));
        configuredOrigin().ifPresent(originValue -> meta.put("authlib",
                Map.of("apiRoot", originValue + AUTHLIB_API_LOCATION)));
        configuredOrigin().ifPresent(originValue -> meta.put("textureBaseUrl", originValue + SKIN_TEXTURE_LOCATION));
        meta.put("signaturePublickey", cryptoService.publicKeyPem());
        meta.put("keyFingerprint", cryptoService.issuerKeyId());
        meta.put("backupSignaturePublickey", null);
        meta.put("features", features());
        meta.put("bans", Map.of("cursor", String.valueOf(repository.banCursor())));
        meta.put("enrollEnabled", configuredOrigin().isPresent() && siteCode().isPresent());
        return meta;
    }

    public Optional<String> configuredOrigin() {
        return frameworkSetting("app.web-url")
                .or(() -> frameworkSetting("app.base-url"))
                .flatMap(this::originOf);
    }

    public Optional<String> siteCode() {
        return stringSetting("site", "siteCode");
    }

    public Optional<String> siteName() {
        return stringSetting("site", "siteName");
    }

    /** 联合名前缀（可空 = 未启用名字空间，对端按 v1 旧语义展示原名）。 */
    public Optional<String> namePrefix() {
        return stringSetting("site", "namePrefix").filter(prefix -> NAME_PREFIX_PATTERN.matcher(prefix).matches());
    }

    public void saveSite(String siteCode, String siteName, String namePrefix) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("siteCode", trim(siteCode));
        document.put("siteName", trim(siteName));
        document.put("namePrefix", trim(namePrefix));
        repository.saveSetting("site", document);
    }

    /** 对端 API 根 + join 查询路径（兼容早期误存的 /peer/meta）。 */
    public static String joinQueryUrl(UnionPeer peer) {
        return apiRoot(peer) + "/peer/joins/query";
    }

    public static String bansUrl(UnionPeer peer) {
        return apiRoot(peer) + "/peer/bans";
    }

    private static String apiRoot(UnionPeer peer) {
        String root = stripTrailingSlash(peer.apiRoot());
        if (root.endsWith("/peer/meta")) {
            return root.substring(0, root.length() - "/peer/meta".length());
        }
        return root;
    }

    private List<String> features() {
        List<String> features = new ArrayList<>();
        features.add("ban-ledger");
        if (!context.extensions(UnionJoinEvidenceProvider.class).isEmpty()) {
            features.add("join-assertions");
        }
        if (namePrefix().isPresent()) {
            features.add(FEATURE_NAME_NAMESPACES);
        }
        return features;
    }

    private Optional<String> frameworkSetting(String key) {
        try {
            return context.framework().setting(key);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<String> stringSetting(String id, String key) {
        return repository.setting(id).map(document -> document.get(key))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(value -> String.valueOf(value));
    }

    private Optional<String> originOf(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            String origin = uri.getScheme() + "://" + uri.getHost();
            if (uri.getPort() >= 0) {
                origin += ":" + uri.getPort();
            }
            return Optional.of(origin);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
