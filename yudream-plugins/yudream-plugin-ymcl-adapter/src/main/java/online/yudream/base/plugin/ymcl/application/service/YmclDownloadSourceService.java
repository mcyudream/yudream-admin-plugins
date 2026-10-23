package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 域下载源分发配置（YAP 下载分发扩展）。
 *
 * <p>管理员在宿主管理端维护「按类别分源 + 有序回退链」，启动器经
 * {@code GET /v1/download/config} 拉取后接管对应资源类别的下载路由——
 * 分发激活期间源顺序由站点统一下发，不归启动器本地设置控制；站点侧
 * 缺类别或关分发时启动器按类别优雅回退本地设置。
 *
 * <p>文档形态（{@link PluginDocumentStore}，落 {@code plugin_ymcl_adapter__*}）：
 * <ul>
 * <li>{@code ymcl_download_config} id {@code active}：分发配置正文
 * <li>{@code ymcl_download_gateway} id {@code secret}：网关对接密钥
 *     （{@code gatewaySecret} 出站共享给镜像网关调 verify/report；
 *     {@code tokenSecret} 仅站内签名下载令牌，永不出站）
 * </ul>
 *
 * <p>协议 JSON 面为 snake_case，与 YAP 其它 face 一致；本服务输出的
 * 视图即协议形状，管理端与启动器共用同一序列化。
 */
public class YmclDownloadSourceService {

    public static final String CONFIG_COLLECTION = "ymcl_download_config";
    public static final String CONFIG_DOC = "active";
    public static final String GATEWAY_COLLECTION = "ymcl_download_gateway";
    public static final String GATEWAY_SECRET_DOC = "secret";

    /** 启动器已识别的 provider 词表；未知 provider 在启动器侧被跳过。 */
    public static final Set<String> PROVIDERS = Set.of(
            "official", "bmclapi", "mcim", "tianpao", "aliyun", "gateway", "custom");
    /** v1 分发的资源类别（对齐启动器 ResourceClass 映射；java/other 不分发）。 */
    public static final Set<String> CATEGORIES = Set.of(
            "minecraft_metadata", "minecraft_files", "loaders", "modrinth", "curseforge");
    public static final Set<String> GATEWAY_AUTH_MODES = Set.of("token", "none");

    private static final int MAX_SOURCES = 16;
    private static final int MAX_PER_CATEGORY = 8;

    private final PluginDocumentStore documents;
    private final SecureRandom random = new SecureRandom();

    public YmclDownloadSourceService(PluginDocumentStore documents) {
        this.documents = documents;
    }

    /** 协议视图（snake_case）。未配置时返回 {@code managed:false} 的空配置。 */
    public Map<String, Object> protocolView() {
        Map<String, Object> stored = documents.findById(CONFIG_COLLECTION, CONFIG_DOC)
                .orElse(null);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("protocol_version", 1);
        boolean managed = stored != null && Boolean.TRUE.equals(asBoolean(stored.get("managed")));
        view.put("managed", managed);
        view.put("updated_at", stored == null ? null : asLong(stored.get("updatedAt")));
        view.put("gateway", gatewayView(stored));
        view.put("official_fallback", stored == null || asBoolean(stored.get("officialFallback")) != Boolean.FALSE);
        view.put("sources", sourceViews(stored));
        view.put("categories", categoryViews(stored));
        return view;
    }

    public boolean isManaged() {
        return documents.findById(CONFIG_COLLECTION, CONFIG_DOC)
                .map(doc -> Boolean.TRUE.equals(asBoolean(doc.get("managed"))))
                .orElse(false);
    }

    /**
     * 保存并校验分发配置。body 为管理端提交的 camelCase 字段
     * （managed / gateway / officialFallback / sources / categories），
     * 返回错误消息；null 表示通过。
     */
    public String save(Map<String, Object> body) {
        if (body == null) {
            return "Request body must be a JSON config object";
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("managed", Boolean.TRUE.equals(asBoolean(body.get("managed"))));

        Object gatewayRaw = body.get("gateway");
        if (gatewayRaw != null && !(gatewayRaw instanceof Map)) {
            return "gateway must be an object";
        }
        Map<?, ?> gateway = gatewayRaw instanceof Map<?, ?> map ? map : Map.of();
        String gatewayBaseUrl = text(gateway.get("baseUrl"));
        String gatewayAuth = text(gateway.get("auth"));
        if (gatewayAuth != null && !GATEWAY_AUTH_MODES.contains(gatewayAuth)) {
            return "gateway.auth must be one of " + GATEWAY_AUTH_MODES;
        }
        if ("token".equals(gatewayAuth) && gatewayBaseUrl == null) {
            return "gateway.baseUrl is required when gateway.auth is token";
        }
        Map<String, Object> gatewayDoc = new LinkedHashMap<>();
        gatewayDoc.put("name", text(gateway.get("name")));
        gatewayDoc.put("baseUrl", gatewayBaseUrl);
        gatewayDoc.put("auth", gatewayAuth == null ? "token" : gatewayAuth);
        doc.put("gateway", gatewayDoc);

        doc.put("officialFallback", asBoolean(body.get("officialFallback")) != Boolean.FALSE);

        Object sourcesRaw = body.get("sources");
        if (!(sourcesRaw instanceof List<?>)) {
            return "sources must be an array";
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (Object item : (List<?>) sourcesRaw) {
            if (sources.size() >= MAX_SOURCES) {
                return "At most " + MAX_SOURCES + " sources are allowed";
            }
            if (!(item instanceof Map<?, ?> source)) {
                return "sources entries must be objects";
            }
            String id = text(source.get("id"));
            String provider = text(source.get("provider"));
            if (id == null || !id.matches("[a-z0-9-]{1,48}")) {
                return "source.id must match [a-z0-9-]{1,48}";
            }
            if (!ids.add(id)) {
                return "Duplicate source id: " + id;
            }
            if (provider == null || !PROVIDERS.contains(provider)) {
                return "source " + id + ": provider must be one of " + PROVIDERS;
            }
            String baseUrl = text(source.get("baseUrl"));
            if ("custom".equals(provider) && (baseUrl == null || !isHttpUrl(baseUrl))) {
                return "source " + id + ": custom provider requires an http(s) baseUrl";
            }
            if ("gateway".equals(provider)) {
                // 网关源缺省继承 gateway.baseUrl 并落值保存，协议视图自包含，
                // 启动器只按 source.base_url 构造路由。
                if (baseUrl == null) {
                    baseUrl = text(gatewayDoc.get("baseUrl"));
                }
                if (baseUrl == null || !isHttpUrl(baseUrl)) {
                    return "source " + id + ": gateway provider requires gateway.baseUrl";
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("provider", provider);
            entry.put("name", text(source.get("name")));
            entry.put("baseUrl", baseUrl);
            sources.add(entry);
        }
        doc.put("sources", sources);

        Object categoriesRaw = body.get("categories");
        if (categoriesRaw != null && !(categoriesRaw instanceof Map)) {
            return "categories must be an object";
        }
        Map<?, ?> categories = categoriesRaw instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Object> categoriesDoc = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            Object listRaw = categories.get(category);
            if (listRaw == null) {
                continue;
            }
            if (!(listRaw instanceof List<?> list)) {
                return "categories." + category + " must be an array of source ids";
            }
            if (list.size() > MAX_PER_CATEGORY) {
                return "categories." + category + ": at most " + MAX_PER_CATEGORY + " sources";
            }
            List<String> ordered = new ArrayList<>();
            for (Object item : list) {
                String id = text(item);
                if (id == null || !ids.contains(id)) {
                    return "categories." + category + " references unknown source id: " + item;
                }
                if (!ordered.contains(id)) {
                    ordered.add(id);
                }
            }
            if (!ordered.isEmpty()) {
                categoriesDoc.put(category, ordered);
            }
        }
        doc.put("categories", categoriesDoc);
        doc.put("updatedAt", System.currentTimeMillis());
        documents.save(CONFIG_COLLECTION, CONFIG_DOC, doc);
        return null;
    }

    // ── 网关对接密钥 ─────────────────────────────────────────────────────

    /** 网关共享密钥（缺失时自动生成），镜像网关调 verify/report 时携带。 */
    public synchronized String gatewaySecret() {
        Map<String, Object> doc = secrets();
        String secret = text(doc.get("gatewaySecret"));
        if (secret != null) {
            return secret;
        }
        String generated = randomToken(32);
        doc.put("gatewaySecret", generated);
        if (text(doc.get("tokenSecret")) == null) {
            doc.put("tokenSecret", randomToken(32));
        }
        doc.put("updatedAt", System.currentTimeMillis());
        documents.save(GATEWAY_COLLECTION, GATEWAY_SECRET_DOC, doc);
        return generated;
    }

    /** 下载令牌签名密钥（仅站内使用，永不出站）。 */
    public synchronized String tokenSecret() {
        Map<String, Object> doc = secrets();
        String secret = text(doc.get("tokenSecret"));
        if (secret != null) {
            return secret;
        }
        String generated = randomToken(32);
        doc.put("tokenSecret", generated);
        doc.put("updatedAt", System.currentTimeMillis());
        documents.save(GATEWAY_COLLECTION, GATEWAY_SECRET_DOC, doc);
        return generated;
    }

    /** 轮换全部网关密钥；返回新的网关共享密钥（旧令牌与网关立即失效）。 */
    public synchronized String rotateSecrets() {
        Map<String, Object> doc = secrets();
        String gatewaySecret = randomToken(32);
        doc.put("gatewaySecret", gatewaySecret);
        doc.put("tokenSecret", randomToken(32));
        doc.put("rotatedAt", System.currentTimeMillis());
        documents.save(GATEWAY_COLLECTION, GATEWAY_SECRET_DOC, doc);
        return gatewaySecret;
    }

    /** 网关共享密钥是否与给定值匹配（常数时间比较）。 */
    public boolean gatewaySecretMatches(String candidate) {
        String secret = gatewaySecret();
        if (candidate == null || secret == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Map<String, Object> secrets() {
        return new LinkedHashMap<>(documents.findById(GATEWAY_COLLECTION, GATEWAY_SECRET_DOC)
                .orElseGet(LinkedHashMap::new));
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    // ── 视图辅助 ─────────────────────────────────────────────────────────

    private static Map<String, Object> gatewayView(Map<String, Object> stored) {
        Map<String, Object> view = new LinkedHashMap<>();
        Object gateway = stored == null ? null : stored.get("gateway");
        Map<?, ?> doc = gateway instanceof Map<?, ?> map ? map : Map.of();
        view.put("name", text(doc.get("name")));
        view.put("base_url", text(doc.get("baseUrl")));
        String auth = text(doc.get("auth"));
        view.put("auth", auth == null ? "token" : auth);
        return view;
    }

    private static List<Map<String, Object>> sourceViews(Map<String, Object> stored) {
        List<Map<String, Object>> views = new ArrayList<>();
        Object sources = stored == null ? null : stored.get("sources");
        if (!(sources instanceof List<?> list)) {
            return views;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> source)) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", text(source.get("id")));
            view.put("provider", text(source.get("provider")));
            view.put("name", text(source.get("name")));
            view.put("base_url", text(source.get("baseUrl")));
            views.add(view);
        }
        return views;
    }

    private static Map<String, Object> categoryViews(Map<String, Object> stored) {
        Map<String, Object> views = new LinkedHashMap<>();
        Object categories = stored == null ? null : stored.get("categories");
        if (!(categories instanceof Map<?, ?> map)) {
            return views;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof List<?> list)) {
                continue;
            }
            views.put(String.valueOf(entry.getKey()), list.stream().map(String::valueOf).toList());
        }
        return views;
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String textValue) {
            return Boolean.parseBoolean(textValue);
        }
        return null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
