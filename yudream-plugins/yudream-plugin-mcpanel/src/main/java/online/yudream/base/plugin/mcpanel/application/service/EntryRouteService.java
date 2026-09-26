package online.yudream.base.plugin.mcpanel.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import online.yudream.base.plugin.mcpanel.application.dto.PanelSettings;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeAccess;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 单端口入口适配（mc-router）：把「域名 → 实例后端」路由推送到入口，并定时对账。
 *
 * <p>mc-router 的 REST API：{@code POST /routes {serverAddress, backend}}、
 * {@code DELETE /routes/{serverAddress}}、{@code GET /routes}（**无鉴权**，必须只在内网/面板可达处暴露）。
 *
 * <p><b>只在使用入口模式时才启用</b>：只有存在「接入方式 = 单端口入口（entry）」且已分配域名的实例、
 * 且面板填了入口地址时，才会推送路由与轮询对账；其它接入方式下面板对这个服务零外呼。
 *
 * <p>对账覆盖三类漂移：router 重启丢路由、实例换端口/换节点、面板与 router 的中断期变更；
 * 只删「域名后缀属于本面板」的多余路由，不碰其它手工路由。
 */
public class EntryRouteService implements AutoCloseable {

    /** 对账周期：路由表每实例一条，1 分钟足够且开销可忽略。 */
    private static final long RECONCILE_MS = 60_000L;

    private final InstanceSource instances;
    private final McpanelNodeRepository nodes;
    private final Supplier<String> suffix;
    private final Supplier<PanelSettings.Entry> entryConfig;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mcpanel-entry-routes");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String lastError = "";

    /** 一条入口路由：{@code {serverAddress, backend}}。 */
    public record Route(String serverAddress, String backend) {
    }

    /** 实例侧最小读口（bootstrap 装配；避免对具体应用服务的硬耦合）。 */
    public interface InstanceSource {
        List<McpanelInstance> all();

        /** 节点直连地址（入口后端兜底地址）：广告地址覆盖 > 端点 host。 */
        String directHostOf(McpanelNode node);
    }

    public EntryRouteService(InstanceSource instances, McpanelNodeRepository nodes,
                             Supplier<PanelSettings.Entry> entryConfig, Supplier<String> suffix) {
        this.instances = instances;
        this.nodes = nodes;
        this.entryConfig = entryConfig;
        this.suffix = suffix;
        this.scheduler.scheduleWithFixedDelay(this::reconcileQuietly, RECONCILE_MS, RECONCILE_MS,
                TimeUnit.MILLISECONDS);
    }

    /** 入口是否已配置（API 地址 + 入口对外地址都有）。 */
    public boolean configured() {
        return disabledReason() == null;
    }

    public String disabledReason() {
        PanelSettings.Entry entry = entryOrNull();
        if (entry == null || !notBlank(entry.apiBase())) {
            return "未配置入口 API 地址（面板设置 → 单端口入口）";
        }
        if (!notBlank(entry.host())) {
            return "未配置入口对外地址（面板设置 → 单端口入口）";
        }
        return null;
    }

    // ---------- 路由下发 / 删除 ----------

    /**
     * 分配/重新同步后推送路由（幂等）：{@code {serverAddress: fqdn, backend: 后端 host:port}}。
     * 仅对入口接入节点的实例生效；返回结果供调用方审计/提示。
     */
    public Map<String, Object> publish(McpanelInstance instance) {
        Route route = routeOf(instance);
        if (route == null) {
            return Map.of("published", false, "reason", configured()
                    ? "该实例不在入口模式（节点接入方式非「单端口入口」或未分配域名）"
                    : disabledReason());
        }
        postRoute(route);
        lastError = "";
        return Map.of("published", true, "serverAddress", route.serverAddress(), "backend", route.backend());
    }

    /** 释放域名时删除路由（幂等：不存在也视为成功）。 */
    public void revoke(McpanelInstance instance) {
        if (!configured()) {
            return;
        }
        String fqdn = instance.domainSlug() == null || instance.domainSlug().isBlank()
                ? "" : instance.domainSlug() + "." + this.suffix.get();
        if (fqdn.isBlank()) {
            return;
        }
        try {
            send("DELETE", entryOrNull().apiBase() + "/routes/"
                    + URLEncoder.encode(fqdn, StandardCharsets.UTF_8), null);
        }
        catch (RuntimeException error) {
            recordFailure(error);
        }
    }

    // ---------- 状态与对账 ----------

    /** 入口状态视图：配置是否齐备、连通性、router 现有路由数、期望路由与差异（设置页用）。 */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        PanelSettings.Entry entry = entryOrNull();
        result.put("configured", configured());
        result.put("reason", disabledReason() == null ? "" : disabledReason());
        result.put("apiBase", entry == null ? "" : entry.apiBase());
        result.put("host", entry == null ? "" : entry.host());
        result.put("port", entry == null || entry.port() <= 0 ? 25565 : entry.port());
        result.put("suffix", this.suffix.get());
        result.put("lastError", lastError);
        if (!configured()) {
            result.put("reachable", false);
            result.put("expectedRoutes", 0);
            return result;
        }
        List<Route> expected = expectedRoutes();
        result.put("expectedRoutes", expected.size());
        try {
            Map<String, String> existing = listRoutes();
            result.put("reachable", true);
            result.put("routerRoutes", existing.size());
            List<Map<String, Object>> drift = new ArrayList<>();
            for (Route route : expected) {
                if (!route.backend().equals(existing.get(route.serverAddress()))) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("serverAddress", route.serverAddress());
                    item.put("expectedBackend", route.backend());
                    item.put("currentBackend", existing.getOrDefault(route.serverAddress(), ""));
                    drift.add(item);
                }
            }
            result.put("drift", drift);
        }
        catch (RuntimeException error) {
            result.put("reachable", false);
            result.put("routerRoutes", 0);
            result.put("message", error.getMessage());
            recordFailure(error);
        }
        return result;
    }

    /** 对账：期望集合 → router；缺失/不一致补推，属于本面板域后缀的多余路由删除。 */
    public Map<String, Object> reconcile() {
        if (!configured()) {
            return Map.of("configured", false, "reason", disabledReason());
        }
        List<Route> expected = expectedRoutes();
        if (expected.isEmpty()) {
            // 没有入口模式实例：完全不碰 router（避免误删别人手工加的路由）。
            return Map.of("configured", true, "expected", 0, "pushed", 0, "removed", 0, "skipped", true);
        }
        Map<String, String> existing = listRoutes();
        int pushed = 0;
        int removed = 0;
        Set<String> expectedNames = new LinkedHashSet<>();
        for (Route route : expected) {
            expectedNames.add(route.serverAddress());
            if (!route.backend().equals(existing.get(route.serverAddress()))) {
                postRoute(route);
                pushed++;
            }
        }
        String suffix = this.suffix.get();
        if (notBlank(suffix)) {
            for (String name : new ArrayList<>(existing.keySet())) {
                if (!expectedNames.contains(name) && name.endsWith("." + suffix)) {
                    send("DELETE", entryOrNull().apiBase() + "/routes/"
                            + URLEncoder.encode(name, StandardCharsets.UTF_8), null);
                    removed++;
                }
            }
        }
        lastError = "";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", true);
        result.put("expected", expected.size());
        result.put("pushed", pushed);
        result.put("removed", removed);
        result.put("routerRoutes", existing.size());
        return result;
    }

    private void reconcileQuietly() {
        try {
            if (!configured()) {
                return; // 未配置入口：零外呼
            }
            if (expectedRoutes().isEmpty()) {
                return; // 没有入口模式实例：同样零外呼，不打扰 router
            }
            reconcile();
        }
        catch (RuntimeException error) {
            recordFailure(error); // 下一轮再试
        }
    }

    /** 面板期望路由：入口接入的节点 + 已分配域名的实例 + 实例 TCP 端口。 */
    List<Route> expectedRoutes() {
        PanelSettings.Entry entry = entryOrNull();
        if (entry == null || !notBlank(entry.host()) || !notBlank(entry.apiBase())) {
            return List.of();
        }
        String suffix = this.suffix.get();
        if (!notBlank(suffix)) {
            return List.of();
        }
        List<Route> routes = new ArrayList<>();
        for (McpanelInstance instance : instances.all()) {
            String slug = instance.domainSlug();
            if (!instance.domainEnabled() || slug == null || slug.isBlank()) {
                continue;
            }
            McpanelNode node = nodes.findById(instance.nodeId()).orElse(null);
            if (node == null || !NodeAccess.MODE_ENTRY.equals(NodeAccess.normalizeMode(node.accessMode()))) {
                continue;
            }
            // 后端地址：节点上填的「入口后端地址」优先（frp 映射到入口机本机时填 127.0.0.1），
            // 否则用节点对外地址（入口机可直连的公网节点）。
            String backendHost = node.accessTarget().isBlank()
                    ? instances.directHostOf(node) : node.accessTarget();
            int port = instance.ports().stream().filter(mapping -> "tcp".equals(mapping.proto()))
                    .mapToInt(McpanelInstance.PortMapping::hostPort).findFirst().orElse(0);
            if (backendHost.isBlank() || port <= 0) {
                continue;
            }
            routes.add(new Route(slug + "." + suffix, backendHost + ":" + port));
        }
        return routes;
    }

    /** 单实例对应的路由（不在入口模式返回 null）。 */
    Route routeOf(McpanelInstance instance) {
        String suffix = this.suffix.get();
        if (!notBlank(suffix) || instance.domainSlug() == null || instance.domainSlug().isBlank()) {
            return null;
        }
        String fqdn = instance.domainSlug() + "." + suffix;
        for (Route route : expectedRoutes()) {
            if (route.serverAddress().equalsIgnoreCase(fqdn)) {
                return route;
            }
        }
        return null;
    }

    // ---------- mc-router HTTP ----------

    private void postRoute(Route route) {
        send("POST", entryOrNull().apiBase() + "/routes",
                "{\"serverAddress\":\"" + route.serverAddress() + "\",\"backend\":\"" + route.backend() + "\"}");
    }

    /** router 当前路由表：serverAddress → backend。 */
    Map<String, String> listRoutes() {
        JsonNode root = read(send("GET", entryOrNull().apiBase() + "/routes", null));
        Map<String, String> routes = new LinkedHashMap<>();
        if (root.isObject()) {
            root.fields().forEachRemaining(item -> routes.put(item.getKey(),
                    item.getValue().path("backend").asText("")));
        }
        return routes;
    }

    private JsonNode read(String body) {
        try {
            return McpanelJson.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
        }
        catch (Exception error) {
            throw new IllegalStateException("入口响应解析失败：" + error.getMessage(), error);
        }
    }

    private String send(String method, String url, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json");
        HttpRequest request = "POST".equals(method)
                ? builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody)).build()
                : "DELETE".equals(method) ? builder.DELETE().build() : builder.GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (Exception error) {
            throw new IllegalStateException("入口（mc-router）不可达：" + error.getMessage(), error);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("入口（mc-router）返回 HTTP " + response.statusCode() + "："
                    + snippet(response.body()));
        }
        return response.body();
    }

    private void recordFailure(RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        lastError = message;
    }

    private PanelSettings.Entry entryOrNull() {
        return entryConfig == null ? null : entryConfig.get();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String text = body.trim().replaceAll("\\s+", " ");
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    @Override
    public void close() {
        scheduler.shutdown();
    }
}
