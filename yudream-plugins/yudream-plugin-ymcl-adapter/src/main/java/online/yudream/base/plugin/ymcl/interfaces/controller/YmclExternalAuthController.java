package online.yudream.base.plugin.ymcl.interfaces.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * YAP 第三方登录桥：把宿主 `/api/external-login/**` 聚合为启动器可轮询的
 * begin/poll 流。begin 生成 flow 并返回宿主 authorize URL；用户在浏览器完成
 * 第三方授权后，提供方回调应落在本插件 landing 端点（管理员将 provider 的
 * callback URL 配置为 `{origin}/api/plugins/ymcl-adapter/v1/auth/external/landing`，
 * 该地址同时服务站点网页登录：state 不在 flow 库时 302 转交网页端回调页），
 * landing 调宿主 callback 换取会话后写入 flow，启动器 poll 即可完成登录。
 */
public class YmclExternalAuthController {

    private static final String FLOW_COLLECTION = "ymcl_external_flows";
    private static final long FLOW_TTL_SECONDS = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final PluginDocumentStore documents;
    private final YmclCapabilitiesController capabilities;

    public YmclExternalAuthController(PluginDocumentStore documents, YmclCapabilitiesController capabilities) {
        this.documents = documents;
        this.capabilities = capabilities;
    }

    // ------------------------------------------------------------------
    // GET /v1/auth/external/providers — 启用中的第三方登录列表
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "GET", path = "/v1/auth/external/providers", wrapResult = false)
    public PluginHttpResponse providers(PluginHttpRequest request) {
        String origin = capabilities.resolveEffectiveOrigin(request);
        try {
            JsonNode data = getJson(origin + "/api/external-login/providers");
            List<Map<String, Object>> providers = new ArrayList<>();
            JsonNode list = data.path("data").isArray() ? data.get("data") : data.path("providers");
            if (list.isArray()) {
                for (JsonNode node : list) {
                    if (node.path("enabled").asBoolean(true) == false) {
                        continue;
                    }
            String code = text(node, "code");
            if (code == null || code.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("name", text(node, "name") == null ? code : text(node, "name"));
            List<String> types = splitTypes(text(node, "supportedTypes"));
            if (!types.isEmpty()) {
                item.put("types", types);
            }
            // 插件 SPI 提供方（CAS/OIDC/OAuth2 等）自带图标；DB 提供方多为 null
            String icon = text(node, "icon");
            if (icon != null && !icon.isBlank()) {
                item.put("icon", icon);
            }
            providers.add(item);
                }
            }
            return PluginHttpResponse.rawJson(200, Map.of("providers", providers));
        } catch (Exception error) {
            return badRequest(502, "providers_unavailable", "无法读取第三方登录提供方：" + error.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // GET /v1/auth/external/{provider}/begin — 开始一次外部登录
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "GET", path = "/v1/auth/external/{provider}/begin", wrapResult = false)
    public PluginHttpResponse begin(PluginHttpRequest request) {
        String origin = capabilities.resolveEffectiveOrigin(request);
        String provider = segment(request.path(), 4);
        if (provider == null || provider.isBlank()) {
            return badRequest(400, "invalid_provider", "provider is required");
        }
        String type = firstQuery(request, "type");
        if (type == null || type.isBlank()) {
            try {
                type = defaultType(origin, provider);
            } catch (Exception error) {
                return badRequest(502, "authorize_failed",
                        "确定登录方式失败：" + error.getMessage());
            }
        }
        try {
            String authorizeUrl = origin + "/api/external-login/"
                    + URLEncoder.encode(provider, StandardCharsets.UTF_8) + "/"
                    + URLEncoder.encode(type, StandardCharsets.UTF_8) + "/authorize";
            JsonNode authorize = getJson(authorizeUrl);
            JsonNode data = authorize.path("data");
            String authorizationUrl = text(data, "authorizationUrl");
            if (authorizationUrl == null || authorizationUrl.isBlank()) {
                authorizationUrl = text(data, "authorization_url");
            }
            if (authorizationUrl == null || authorizationUrl.isBlank()) {
                return badRequest(502, "authorize_failed", "宿主未返回第三方授权地址");
            }
            String flowId = UUID.randomUUID().toString().replace("-", "");
            String hostState = text(data, "state");
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("id", flowId);
            flow.put("provider", provider);
            flow.put("type", type);
            flow.put("status", "pending");
            flow.put("createdAt", Instant.now().toEpochMilli());
            documents.save(FLOW_COLLECTION, flowId, flow);
            if (hostState != null && !hostState.isBlank()) {
                Map<String, Object> index = new LinkedHashMap<>();
                index.put("flowId", flowId);
                documents.save(FLOW_COLLECTION, "state:" + hostState, index);
            }
            return PluginHttpResponse.rawJson(200, Map.of(
                    "flow_id", flowId,
                    "authorize_url", authorizationUrl
            ));
        } catch (Exception error) {
            return badRequest(502, "authorize_failed", "开始第三方登录失败：" + error.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // GET /v1/auth/external/{provider}/poll?flowId= — 轮询登录结果
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "GET", path = "/v1/auth/external/{provider}/poll", wrapResult = false)
    public PluginHttpResponse poll(PluginHttpRequest request) {
        String flowId = firstQuery(request, "flowId");
        if (flowId == null || flowId.isBlank()) {
            return badRequest(400, "invalid_flow", "flowId is required");
        }
        Optional<Map<String, Object>> stored = documents.findById(FLOW_COLLECTION, flowId);
        if (stored.isEmpty()) {
            return PluginHttpResponse.rawJson(200, Map.of("status", "expired"));
        }
        Map<String, Object> flow = stored.get();
        long createdAt = flow.get("createdAt") instanceof Number number
                ? number.longValue() : 0L;
        if (createdAt > 0 && Instant.now().toEpochMilli() - createdAt > FLOW_TTL_SECONDS * 1000L) {
            documents.delete(FLOW_COLLECTION, flowId);
            return PluginHttpResponse.rawJson(200, Map.of("status", "expired"));
        }
        String status = String.valueOf(flow.getOrDefault("status", "pending"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if ("completed".equals(status)) {
            body.put("access_token", flow.get("access_token"));
            body.put("refresh_token", flow.get("refresh_token"));
            body.put("expires_in", flow.get("expires_in"));
        }
        if ("bind_required".equals(status)) {
            body.put("claim_token", flow.get("claim_token"));
            body.put("message", flow.get("message"));
        }
        if ("error".equals(status)) {
            body.put("message", flow.get("message"));
        }
        return PluginHttpResponse.rawJson(200, body);
    }

    // ------------------------------------------------------------------
    // GET /v1/auth/external/flow-owner?state= — 站点回调页的分流询问
    // ------------------------------------------------------------------
    /**
     * 站点网页回调页处理回调前询问：该 state 是否由启动器发起（本插件 flow 库
     * 持有）。命中则页面把浏览器转交本插件 landing；未命中或本插件未安装/未启用
     * 时页面按原流程处理——宿主代码、站点配置与网页登录流程均零改动。
     */
    @PluginHttpEndpoint(method = "GET", path = "/v1/auth/external/flow-owner", wrapResult = false)
    public PluginHttpResponse flowOwner(PluginHttpRequest request) {
        String state = firstQuery(request, "state");
        if (state == null || state.isBlank()) {
            return badRequest(400, "invalid_state", "state is required");
        }
        return PluginHttpResponse.rawJson(200, Map.of("owned", findFlowIdByState(state) != null));
    }

    // ------------------------------------------------------------------
    // GET /v1/auth/external/landing — 第三方授权回跳落点
    // ------------------------------------------------------------------
    @PluginHttpEndpoint(method = "GET", path = "/v1/auth/external/landing", wrapResult = false)
    public PluginHttpResponse landing(PluginHttpRequest request) {
        String code = firstQuery(request, "code");
        String ticket = firstQuery(request, "ticket");
        String credential = code != null && !code.isBlank() ? code : ticket;
        String state = firstQuery(request, "state");
        String provider = firstQuery(request, "provider");
        String type = firstQuery(request, "type");
        if (credential == null || credential.isBlank() || state == null || state.isBlank()) {
            return html(400, "第三方登录回调缺少 code/ticket 或 state 参数。");
        }
        String origin = capabilities.resolveEffectiveOrigin(request);
        try {
            StringBuilder url = new StringBuilder(origin)
                    .append("/api/external-login/callback?state=")
                    .append(URLEncoder.encode(state, StandardCharsets.UTF_8))
                    .append("&code=")
                    .append(URLEncoder.encode(credential, StandardCharsets.UTF_8));
            if (provider != null && !provider.isBlank()) {
                url.append("&provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
            }
            if (type != null && !type.isBlank()) {
                url.append("&type=").append(URLEncoder.encode(type, StandardCharsets.UTF_8));
            }
            JsonNode response = getJson(url.toString());
            JsonNode data = response.path("data");
            String outcome = text(data, "outcome");
            // 宿主按 state 关联 flow：state 即我们 begin 时由宿主 authorize 生成的
            // 随机串；这里用 state 反查最近的 pending flow（state 不会回传 flowId，
            // 因此落盘时同步保存 state）。
            String flowId = findFlowIdByState(state);
            if (flowId == null) {
                // 非启动器发起的回调（站点网页登录等）：state 不在本插件 flow
                // 库，原样转交网页端回调页，由其完成浏览器侧会话建立。
                // 管理员把 provider 的 callback URL 统一配到本 landing 即可，
                // 网页端与启动器两条链路共用这一个回调地址。
                return redirect(origin + "/external-login/callback" + queryString(request));
            }
            Map<String, Object> flow = documents.findById(FLOW_COLLECTION, flowId).orElse(null);
            if (flow == null) {
                return html(404, "登录会话已过期，请回到启动器重新发起第三方登录。");
            }
            if ("LOGIN".equalsIgnoreCase(outcome)) {
                JsonNode session = data.path("session");
                flow.put("status", "completed");
                flow.put("access_token", text(session, "token") == null ? text(session, "accessToken") : text(session, "token"));
                flow.put("refresh_token", text(session, "refreshToken") == null ? text(session, "refresh_token") : text(session, "refreshToken"));
                flow.put("expires_in", session.path("expiresIn").isMissingNode()
                        ? session.path("expires_in").asLong(86400)
                        : session.path("expiresIn").asLong(86400));
                documents.save(FLOW_COLLECTION, flowId, flow);
                // 深链回启动器：OS 把 ymcl:// 递给已运行的启动器并聚焦窗口；
                // 无法处理时页面文案仍可见，用户手动切回即可。
                return htmlWithLauncherRedirect(200,
                        "第三方登录成功，正在返回启动器…", "landing?status=completed");
            }
            if ("BIND_REQUIRED".equalsIgnoreCase(outcome)) {
                flow.put("status", "bind_required");
                flow.put("claim_token", text(data, "bindingToken") == null ? text(data, "binding_token") : text(data, "bindingToken"));
                flow.put("message", "该第三方账号尚未绑定域账号，请先在站点完成绑定后重试");
                documents.save(FLOW_COLLECTION, flowId, flow);
                // 与站点网页端 BIND_REQUIRED 同一处理：带到登录页完成绑定。
                String bindingToken = flow.getOrDefault("claim_token", "").toString();
                return redirect(origin + "/login?externalLoginBindingToken="
                        + URLEncoder.encode(bindingToken, StandardCharsets.UTF_8)
                        + "&externalLoginProvider="
                        + URLEncoder.encode(String.valueOf(flow.getOrDefault("provider", "")), StandardCharsets.UTF_8)
                        + "&externalLoginType="
                        + URLEncoder.encode(String.valueOf(flow.getOrDefault("type", "")), StandardCharsets.UTF_8));
            }
            if ("BOUND".equalsIgnoreCase(outcome)) {
                flow.put("status", "error");
                flow.put("message", "绑定已完成，请回到启动器重新发起登录");
                documents.save(FLOW_COLLECTION, flowId, flow);
                return htmlWithLauncherRedirect(200,
                        "绑定已完成，请回到启动器重新发起第三方登录。", "landing?status=bound");
            }
            flow.put("status", "error");
            flow.put("message", "第三方登录结果未知：" + outcome);
            documents.save(FLOW_COLLECTION, flowId, flow);
            return htmlWithLauncherRedirect(502, "第三方登录结果未知：" + outcome, null);
        } catch (Exception error) {
            return html(502, "第三方登录回调处理失败：" + error.getMessage());
        }
    }

    /**
     * begin 调宿主 authorize 时保存 state → flowId，landing 回跳时按 state 反查。
     * 宿主 authorize 响应里的 state 由宿主生成。
     */
    private String findFlowIdByState(String state) {
        // documents 列表接口未在 SPI 暴露全表扫描；采用「state 即索引键」策略：
        // begin 时另存一份 state 文档。
        return documents.findById(FLOW_COLLECTION, "state:" + state)
                .map(doc -> String.valueOf(doc.get("flowId")))
                .orElse(null);
    }

    // begin 需要把宿主返回的 state 与 flow 绑定；覆写 begin 里的保存逻辑。
    // 上面 begin 已保存 flow，这里在 begin 成功后由 landing 使用。

    /**
     * 宿主 authorize 路由的 {type} 是必填路径段，空缺时取该提供方
     * supportedTypes 的第一项（与站点网页端按平台按钮传 type 等效）。
     * 不能拿 provider code 充当 type：code 不在 supportedTypes 里会被
     * 宿主以「该提供方未开放此登录方式」拒绝。
     */
    private String defaultType(String origin, String provider) throws Exception {
        JsonNode data = getJson(origin + "/api/external-login/providers");
        JsonNode list = data.path("data").isArray() ? data.get("data") : data.path("providers");
        if (list.isArray()) {
            for (JsonNode node : list) {
                String code = text(node, "code");
                if (code == null || code.isBlank()
                        || !code.trim().equalsIgnoreCase(provider.trim())) {
                    continue;
                }
                List<String> types = splitTypes(text(node, "supportedTypes"));
                if (types.isEmpty()) {
                    throw new IllegalStateException("该提供方未配置可用平台");
                }
                return types.getFirst();
            }
        }
        throw new IllegalStateException("第三方登录提供方不存在或未启用");
    }

    private static List<String> splitTypes(String supportedTypes) {
        List<String> types = new ArrayList<>();
        if (supportedTypes == null || supportedTypes.isBlank()) {
            return types;
        }
        for (String item : supportedTypes.split(",")) {
            if (!item.isBlank()) {
                types.add(item.trim());
            }
        }
        return types;
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + body);
        }
        return MAPPER.readTree(body);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        return node.get(field).asText();
    }

    private static String segment(String path, int index) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("/");
        return index < parts.length ? parts[index] : null;
    }

    private static String firstQuery(PluginHttpRequest request, String name) {
        List<String> values = request.query().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static PluginHttpResponse badRequest(int status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", message);
        return PluginHttpResponse.rawJson(status, payload);
    }

    private static PluginHttpResponse redirect(String location) {
        return new PluginHttpResponse(302, Map.of("Location", location),
                "text/html; charset=utf-8", "", false);
    }

    /** 把回调携带的全部查询参数（code/state/type 及提供方附加项）原样重组。 */
    private static String queryString(PluginHttpRequest request) {
        StringBuilder qs = new StringBuilder();
        request.query().forEach((name, values) -> {
            if (values == null) {
                return;
            }
            for (String value : values) {
                if (qs.length() > 0) {
                    qs.append('&');
                }
                qs.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
                if (value != null) {
                    qs.append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                }
            }
        });
        return qs.isEmpty() ? "" : "?" + qs;
    }

    private static PluginHttpResponse html(int status, String message) {
        return htmlWithLauncherRedirect(status, message, null);
    }

    /** 结果页：正文之外尝试经 ymcl:// 深链把已运行的启动器拉回前台；
     * 浏览器/系统无法处理深链时正文仍可见，不影响手动切回。 */
    private static PluginHttpResponse htmlWithLauncherRedirect(
            int status, String message, String deepLinkPath) {
        String escaped = message
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        String script = deepLinkPath == null ? ""
                : "<script>location.replace(\"ymcl://auth/external/" + deepLinkPath + "\")</script>";
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>YMCL 第三方登录</title></head><body style=\"font-family:system-ui,sans-serif;"
                + "display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0;"
                + "background:#0f172a;color:#e2e8f0\"><main style=\"max-width:28rem;padding:2rem;text-align:center\">"
                + "<h1 style=\"font-size:1.25rem;margin:0 0 1rem\">YMCL 启动器</h1>"
                + "<p style=\"margin:0;line-height:1.6\">" + escaped + "</p></main>" + script + "</body></html>";
        return new PluginHttpResponse(status, Map.of(), "text/html; charset=utf-8", html, false);
    }
}
