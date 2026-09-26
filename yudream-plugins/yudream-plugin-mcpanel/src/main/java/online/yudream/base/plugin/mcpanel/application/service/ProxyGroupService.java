package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 代理网络纳管（BungeeCord / Velocity）：
 * - detect：读实例根目录识别代理类型（velocity.toml / config.yml），解析 servers
 *   段并按「节点地址 + 已分配端口」自动匹配面板实例（localhost 视为同节点）；
 * - save/delete：纳管与解除，绑定关系存独立文档集合（不进实例 config，避免节点
 *   单值 8KB 限制与节点载荷污染）；绑定唯一（一个子服只能挂一个代理，且不能成环）；
 * - decorate：给实例列表/详情 DTO 附加 proxy / proxyChildren 摘要。
 *
 * 注意：节点没有文件监听，管理员手改代理配置后需在详情页「重新识别」刷新模型；
 * 识别只读不写，纳管保存不会改写代理配置文件本身（子服写入是后续版本能力）。
 */
public class ProxyGroupService {

    public interface FilesGateway {
        Map<String, Object> invoke(String scopeKey, String instanceId, String method, Map<String, Object> args);
    }

    private static final String COLLECTION = "mcpanel_proxy_groups";
    private static final int MAX_SERVERS = 64;
    private static final long CALL_LIST_LIMIT = 500;

    private final PluginDocumentStore documents;
    private final McpanelInstanceRepository instanceRepository;
    private final McpanelNodeRepository nodeRepository;
    private final FilesGateway files;
    private final McpanelInstanceAppService.AuditRecorder audit;

    public ProxyGroupService(PluginDocumentStore documents, McpanelInstanceRepository instanceRepository,
                             McpanelNodeRepository nodeRepository, FilesGateway files,
                             McpanelInstanceAppService.AuditRecorder audit) {
        this.documents = documents;
        this.instanceRepository = instanceRepository;
        this.nodeRepository = nodeRepository;
        this.files = files;
        this.audit = audit;
    }

    // ---------- 识别（只读） ----------

    public Map<String, Object> detect(String scopeKey, String instanceId) {
        McpanelInstance proxy = requireInstance(instanceId);
        List<String> rootNames = listRootNames(scopeKey, instanceId);
        boolean hasVelocity = rootNames.contains("velocity.toml");
        boolean hasBungee = rootNames.contains("config.yml");
        boolean hasServerProperties = rootNames.contains("server.properties");

        String kind;
        String sourcePath;
        if (hasVelocity) {
            kind = "velocity";
            sourcePath = "velocity.toml";
        }
        else if (hasBungee && !hasServerProperties) {
            // MC 后端根目录不会出现 config.yml（插件自己的 config.yml 在子目录），
            // 根级 config.yml + 无 server.properties 即 BungeeCord 家族特征。
            kind = "bungee";
            sourcePath = "config.yml";
        }
        else {
            Map<String, Object> none = new LinkedHashMap<>();
            none.put("detected", false);
            none.put("reason", hasServerProperties
                    ? "该实例看起来是 MC 服务端（存在 server.properties），不是代理"
                    : "未在实例根目录找到代理配置特征（velocity.toml / config.yml）");
            return none;
        }

        StructuredConfigCodec.Format format = StructuredConfigCodec.formatOf(sourcePath);
        Map<String, Object> tree = readTree(scopeKey, instanceId, sourcePath, format);
        List<Map<String, Object>> servers = new ArrayList<>();
        String defaultServer = "";
        String forwarding = "";
        Object serversNode = tree.get("servers");
        Map<?, ?> serversMap = serversNode instanceof Map<?, ?> cast ? cast : null;
        if (serversMap != null) {
            for (Map.Entry<?, ?> entry : serversMap.entrySet()) {
                String name = String.valueOf(entry.getKey());
                String address = serverAddress(entry.getValue());
                if (name.isBlank() || address == null) {
                    continue;
                }
                servers.add(newServer(name, address));
            }
        }
        if ("velocity".equals(kind)) {
            // velocity.toml 的 try 列表位于 [servers] 表内。
            defaultServer = firstListItem(serversMap == null ? null : serversMap.get("try"));
            Object mode = tree.get("player-info-forwarding");
            forwarding = mode == null ? "" : String.valueOf(mode);
        }
        else {
            defaultServer = firstListItem(tree.get("priorities"));
            forwarding = Boolean.parseBoolean(String.valueOf(tree.get("ip-forward"))) ? "legacy" : "none";
        }
        if (defaultServer.isBlank() && !servers.isEmpty()) {
            defaultServer = String.valueOf(servers.get(0).get("name"));
        }

        // 匹配：地址命中自动绑定；名称唯一同名给出建议（前端确认后保存）。
        List<McpanelInstance> candidates = instanceRepository.findAll().stream()
                .filter(item -> !item.id().equals(proxy.id()))
                .toList();
        Function<String, String> endpointHost = this::endpointHostOf;
        for (Map<String, Object> server : servers) {
            String address = String.valueOf(server.get("address"));
            String matchedId = matchByAddress(address, proxy.nodeId(), candidates, endpointHost);
            String matchType = matchedId == null ? "" : "address";
            if (matchedId == null) {
                List<McpanelInstance> sameName = candidates.stream()
                        .filter(item -> item.name().equalsIgnoreCase(String.valueOf(server.get("name"))))
                        .toList();
                if (sameName.size() == 1) {
                    matchedId = sameName.get(0).id();
                    matchType = "name";
                }
            }
            if (matchedId != null) {
                instanceRepository.findById(matchedId).ifPresent(matched -> {
                    server.put("matchedInstanceId", matched.id());
                    server.put("matchedName", matched.name());
                });
                server.put("matchType", matchType);
            }
            else {
                server.put("matchType", "none");
            }
        }

        // 已纳管时沿用管理员确认过的绑定（名称+地址未变的行）。
        Map<String, Object> existing = documents.findById(COLLECTION, instanceId).orElse(null);
        if (existing != null) {
            Object existingServers = existing.get("servers");
            if (existingServers instanceof List<?> rows) {
                Map<String, String> carry = new HashMap<>();
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> serverRow
                            && serverRow.get("boundInstanceId") != null
                            && serverRow.get("address") != null) {
                        carry.put(String.valueOf(serverRow.get("name")) + "|" + String.valueOf(serverRow.get("address")),
                                String.valueOf(serverRow.get("boundInstanceId")));
                    }
                }
                for (Map<String, Object> server : servers) {
                    String carried = carry.get(server.get("name") + "|" + server.get("address"));
                    if (carried != null) {
                        server.put("matchedInstanceId", carried);
                        server.put("matchType", "carried");
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detected", true);
        result.put("kind", kind);
        result.put("sourcePath", sourcePath);
        result.put("forwarding", forwarding);
        result.put("defaultServer", defaultServer);
        result.put("servers", servers);
        return result;
    }

    // ---------- 纳管状态 ----------

    public Map<String, Object> group(String instanceId) {
        Map<String, Object> doc = documents.findById(COLLECTION, instanceId).orElse(null);
        if (doc == null) {
            return null;
        }
        return decorateGroup(doc);
    }

    /** 已纳管代理列表（创建向导「挂到代理」选择器数据源）。 */
    public List<Map<String, Object>> listGroups() {
        Map<String, McpanelInstance> instances = indexInstances();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> group : loadGroups()) {
            String proxyId = String.valueOf(group.get("proxyInstanceId"));
            McpanelInstance proxy = instances.get(proxyId);
            int bound = 0;
            Object rows = group.get("servers");
            if (rows instanceof List<?> serverRows) {
                bound = (int) serverRows.stream().filter(row -> row instanceof Map<?, ?> server
                        && server.get("boundInstanceId") != null).count();
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("proxyInstanceId", proxyId);
            item.put("proxyName", proxy == null ? proxyId : proxy.name());
            item.put("kind", group.get("kind"));
            item.put("serverCount", bound);
            out.add(item);
        }
        return out;
    }

    /**
     * 创建子服后自动挂到代理：组绑定 + 把子服地址写进代理配置文件
     * （velocity.toml servers.&lt;name&gt; / config.yml servers.&lt;name&gt;.address）。
     * 地址 = 子服所在节点 endpoint host + 已分配宿主端口（方案 1：零节点改动的互通路径）。
     * 配置写入失败不回滚绑定（关系已建立，返回 configWritten=false 由前端提示手动处理）。
     */
    public Map<String, Object> attachChild(String actor, String scopeKey, String proxyId, String childId) {
        McpanelInstance proxy = requireInstance(proxyId);
        McpanelInstance child = instanceRepository.findById(childId)
                .orElseThrow(() -> McpanelBusinessException.invalid("子服实例不存在"));
        if (childId.equals(proxyId)) {
            throw McpanelBusinessException.invalid("代理不能绑定自己");
        }
        String conflict = otherBindingOf(childId, proxyId);
        if (conflict != null) {
            throw McpanelBusinessException.invalid("该实例已绑定在代理 " + conflict + " 上");
        }
        if (documents.findById(COLLECTION, childId).isPresent()) {
            throw McpanelBusinessException.invalid("该实例自身是代理，不支持代理串联");
        }
        Map<String, Object> group = documents.findById(COLLECTION, proxyId).orElse(null);
        if (group == null) {
            throw McpanelBusinessException.invalid("代理尚未纳管：请先在代理实例详情页「识别代理配置」");
        }
        McpanelInstance.PortMapping port = child.ports().stream()
                .filter(mapping -> "tcp".equals(mapping.proto()))
                .findFirst()
                .orElseThrow(() -> McpanelBusinessException.invalid("子服没有已分配的 TCP 端口"));
        String host = endpointHostOf(child.nodeId());
        if (host.isBlank()) {
            throw McpanelBusinessException.invalid("子服所在节点地址未知（endpoint 未配置）");
        }
        String address = host + ":" + port.hostPort();
        String kind = String.valueOf(group.get("kind"));
        List<Map<String, Object>> servers = new ArrayList<>();
        Object rows = group.get("servers");
        if (rows instanceof List<?> serverRows) {
            for (Object row : serverRows) {
                if (row instanceof Map<?, ?> serverRow) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> server = new LinkedHashMap<>((Map<String, Object>) serverRow);
                    servers.add(server);
                }
            }
        }
        String serverName = uniqueServerName(child.name(), servers);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", serverName);
        server.put("address", address);
        server.put("boundInstanceId", childId);
        server.put("external", false);
        servers.add(server);
        group.put("servers", servers);
        group.put("updatedAt", System.currentTimeMillis());
        documents.save(COLLECTION, proxyId, group);
        if (audit != null) {
            audit.record(actor, "proxy.group.attach", "instance", proxyId,
                    "子服 " + serverName + " → " + address, proxy.tenantId());
        }
        boolean configWritten = writeProxyServer(scopeKey, proxy, kind, serverName, address);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registered", true);
        result.put("serverName", serverName);
        result.put("address", address);
        result.put("configWritten", configWritten);
        result.put("sourcePath", "velocity".equals(kind) ? "velocity.toml" : "config.yml");
        return result;
    }

    /** 组内子服名去重：同名自动追加 -2/-3。 */
    private static String uniqueServerName(String raw, List<Map<String, Object>> servers) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Map<String, Object> server : servers) {
            taken.add(String.valueOf(server.get("name")).toLowerCase(Locale.ROOT));
        }
        String base = raw == null || raw.isBlank() ? "server" : raw.trim();
        String candidate = base;
        int suffix = 2;
        while (taken.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    /** 把子服写进代理配置（点路径结构化合并；失败返回 false 不抛）。 */
    private boolean writeProxyServer(String scopeKey, McpanelInstance proxy, String kind,
                                     String serverName, String address) {
        String path = "velocity".equals(kind) ? "velocity.toml" : "config.yml";
        StructuredConfigCodec.Format format = StructuredConfigCodec.formatOf(path);
        try {
            Map<String, Object> raw = files.invoke(scopeKey, proxy.id(), "read", Map.of("path", path));
            Object encoded = raw.get("content");
            if (encoded == null || String.valueOf(encoded).isBlank()) {
                return false; // 配置尚未生成（代理没启动过）：绑定已保存，管理员启动后再识别
            }
            String content = new String(Base64.getDecoder().decode(String.valueOf(encoded)), StandardCharsets.UTF_8);
            Map<String, Object> tree = StructuredConfigCodec.parseTree(content, format);
            Map<String, String> edits = new LinkedHashMap<>();
            if ("velocity".equals(kind)) {
                edits.put("servers." + serverName, address);
            }
            else {
                edits.put("servers." + serverName + ".address", address);
            }
            StructuredConfigCodec.applyEdits(tree, edits);
            String written = StructuredConfigCodec.writeTree(tree, format);
            files.invoke(scopeKey, proxy.id(), "write", Map.of(
                    "path", path,
                    "content", Base64.getEncoder().encodeToString(written.getBytes(StandardCharsets.UTF_8)),
                    "encoding", "base64"));
            return true;
        }
        catch (RuntimeException | IOException | LinkageError error) {
            return false;
        }
    }

    public Map<String, Object> save(String actor, String instanceId, Map<String, Object> body) {
        McpanelInstance proxy = requireInstance(instanceId);
        String kind = String.valueOf(body.getOrDefault("kind", "")).trim();
        if (!"velocity".equals(kind) && !"bungee".equals(kind)) {
            throw McpanelBusinessException.invalid("代理类型仅支持 velocity / bungee");
        }
        List<Map<String, Object>> servers = parseServers(body.get("servers"));
        Map<String, String> byId = new HashMap<>();
        Map<String, String> seenNames = new HashMap<>();
        for (Map<String, Object> server : servers) {
            String name = String.valueOf(server.get("name")).trim();
            if (name.isEmpty() || name.length() > 64) {
                throw McpanelBusinessException.invalid("子服名称需为 1-64 个字符");
            }
            if (seenNames.put(name.toLowerCase(Locale.ROOT), name) != null) {
                throw McpanelBusinessException.invalid("子服名称重复：" + name);
            }
            String address = String.valueOf(server.getOrDefault("address", "")).trim();
            if (address.length() > 128) {
                throw McpanelBusinessException.invalid("子服地址过长：" + name);
            }
            server.put("name", name);
            server.put("address", address);
            String boundId = String.valueOf(server.getOrDefault("boundInstanceId", "")).trim();
            if (boundId.isEmpty() || "null".equals(boundId)) {
                server.remove("boundInstanceId");
                server.put("external", true);
                continue;
            }
            if (boundId.equals(instanceId)) {
                throw McpanelBusinessException.invalid("代理不能绑定自己：" + name);
            }
            McpanelInstance bound = instanceRepository.findById(boundId)
                    .orElseThrow(() -> McpanelBusinessException.invalid("绑定的实例不存在：" + name));
            if (byId.put(boundId, name) != null) {
                throw McpanelBusinessException.invalid("同一子服实例被绑定到多个条目：" + bound.name());
            }
            String conflict = otherBindingOf(boundId, instanceId);
            if (conflict != null) {
                throw McpanelBusinessException.invalid(
                        "实例「" + bound.name() + "」已绑定在代理 " + conflict + " 上");
            }
            if (documents.findById(COLLECTION, boundId).isPresent()) {
                throw McpanelBusinessException.invalid(
                        "实例「" + bound.name() + "」自身是代理，不支持代理串联");
            }
            server.put("boundInstanceId", boundId);
            server.put("external", false);
        }
        String defaultServer = String.valueOf(body.getOrDefault("defaultServer", "")).trim();
        if (!defaultServer.isEmpty() && seenNames.get(defaultServer.toLowerCase(Locale.ROOT)) == null) {
            throw McpanelBusinessException.invalid("默认子服不在子服列表中：" + defaultServer);
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("proxyInstanceId", instanceId);
        doc.put("kind", kind);
        doc.put("sourcePath", "velocity".equals(kind) ? "velocity.toml" : "config.yml");
        doc.put("servers", servers);
        doc.put("defaultServer", defaultServer);
        doc.put("forwarding", String.valueOf(body.getOrDefault("forwarding", "")).trim());
        Map<String, Object> existing = documents.findById(COLLECTION, instanceId).orElse(null);
        long now = System.currentTimeMillis();
        doc.put("createdAt", existing == null || existing.get("createdAt") == null
                ? now : ((Number) existing.get("createdAt")).longValue());
        doc.put("updatedAt", now);
        documents.save(COLLECTION, instanceId, doc);
        if (audit != null) {
            audit.record(actor, "proxy.group.save", "instance", instanceId,
                    kind + " 子服 " + servers.size() + " 个", proxy.tenantId());
        }
        return decorateGroup(doc);
    }

    public void delete(String actor, String instanceId) {
        McpanelInstance proxy = requireInstance(instanceId);
        if (documents.findById(COLLECTION, instanceId).isEmpty()) {
            return;
        }
        documents.delete(COLLECTION, instanceId);
        if (audit != null) {
            audit.record(actor, "proxy.group.delete", "instance", instanceId, "解除代理纳管", proxy.tenantId());
        }
    }

    /** 代理实例被删除时清理组文档（子服侧装饰自然消失）。 */
    public void evictGroup(String instanceId) {
        try {
            documents.delete(COLLECTION, instanceId);
        }
        catch (RuntimeException ignored) {
            // 文档缺失等：清理尽力而为。
        }
    }

    // ---------- 实例 DTO 装饰（父子关系摘要） ----------

    public void decoratePage(Map<String, Object> pageResult) {
        Object records = pageResult.get("records");
        if (!(records instanceof List<?> rows)) {
            return;
        }
        List<Map<String, Object>> groups = loadGroups();
        Map<String, McpanelInstance> instances = indexInstances();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> record) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dto = (Map<String, Object>) record;
                decorate(dto, groups, instances);
            }
        }
    }

    public void decorateDetail(Map<String, Object> dto) {
        decorate(dto, loadGroups(), indexInstances());
    }

    private void decorate(Map<String, Object> dto, List<Map<String, Object>> groups,
                          Map<String, McpanelInstance> instances) {
        String id = String.valueOf(dto.get("id"));
        for (Map<String, Object> group : groups) {
            if (id.equals(group.get("proxyInstanceId"))) {
                List<Map<String, Object>> children = new ArrayList<>();
                Object rows = group.get("servers");
                if (rows instanceof List<?> serverRows) {
                    for (Object row : serverRows) {
                        if (row instanceof Map<?, ?> server && server.get("boundInstanceId") != null) {
                            String childId = String.valueOf(server.get("boundInstanceId"));
                            McpanelInstance child = instances.get(childId);
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("serverName", server.get("name"));
                            item.put("instanceId", childId);
                            item.put("instanceName", child == null ? childId : child.name());
                            item.put("state", child == null ? "" : child.state());
                            children.add(item);
                        }
                    }
                }
                dto.put("proxyChildren", children);
            }
            Object rows = group.get("servers");
            if (rows instanceof List<?> serverRows) {
                for (Object row : serverRows) {
                    if (row instanceof Map<?, ?> server
                            && id.equals(server.get("boundInstanceId") == null ? "" : String.valueOf(server.get("boundInstanceId")))) {
                        String proxyId = String.valueOf(group.get("proxyInstanceId"));
                        McpanelInstance proxy = instances.get(proxyId);
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("proxyInstanceId", proxyId);
                        info.put("proxyName", proxy == null ? proxyId : proxy.name());
                        info.put("serverName", server.get("name"));
                        dto.put("proxy", info);
                    }
                }
            }
        }
    }

    // ---------- 地址匹配（纯函数，测试直接覆盖） ----------

    /**
     * host:port → 实例 ID。
     * host 为 localhost/127.0.0.1/0.0.0.0/::1 时视为「同节点」，按代理节点 + 已分配
     * 宿主端口匹配；否则按候选实例所在节点 endpoint host 匹配。多个候选命中（歧义）
     * 一律不自动绑定，交由管理员在前端手动选择。
     */
    static String matchByAddress(String address, String proxyNodeId, List<McpanelInstance> candidates,
                                 Function<String, String> endpointHost) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(colon + 1).trim());
        }
        catch (NumberFormatException error) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        String host = normalizeHost(trimmed.substring(0, colon));
        boolean local = isLocalHost(host);
        String matched = null;
        for (McpanelInstance candidate : candidates) {
            boolean portHit = candidate.ports().stream()
                    .anyMatch(mapping -> "tcp".equals(mapping.proto()) && mapping.hostPort() == port);
            if (!portHit) {
                continue;
            }
            boolean hostHit = local
                    ? candidate.nodeId().equals(proxyNodeId)
                    : host.equals(normalizeHost(endpointHost.apply(candidate.nodeId())));
            if (!hostHit) {
                continue;
            }
            if (matched != null) {
                return null; // 歧义：不猜。
            }
            matched = candidate.id();
        }
        return matched;
    }

    static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    static boolean isLocalHost(String normalizedHost) {
        return "localhost".equals(normalizedHost) || "127.0.0.1".equals(normalizedHost)
                || "0.0.0.0".equals(normalizedHost) || "::1".equals(normalizedHost);
    }

    // ---------- 内部 ----------

    private String endpointHostOf(String nodeId) {
        return nodeRepository.findById(nodeId)
                .map(McpanelNode::endpoint)
                .map(ProxyGroupService::hostOfEndpoint)
                .orElse("");
    }

    /** wss://host:port/control → host（小写；无 scheme 时按原串处理）。 */
    static String hostOfEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "";
        }
        String value = endpoint.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            value = value.substring(0, colon);
        }
        return normalizeHost(value);
    }

    private McpanelInstance requireInstance(String instanceId) {
        return instanceRepository.findById(instanceId)
                .orElseThrow(() -> McpanelBusinessException.notFound("实例不存在"));
    }

    private List<String> listRootNames(String scopeKey, String instanceId) {
        Map<String, Object> result = files.invoke(scopeKey, instanceId, "list",
                Map.of("path", "", "page", 1, "size", CALL_LIST_LIMIT));
        List<String> names = new ArrayList<>();
        Object entries = result.get("entries");
        if (entries instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> entry && entry.get("name") != null) {
                    names.add(String.valueOf(entry.get("name")));
                }
            }
        }
        return names;
    }

    private Map<String, Object> readTree(String scopeKey, String instanceId, String path,
                                         StructuredConfigCodec.Format format) {
        Map<String, Object> raw = files.invoke(scopeKey, instanceId, "read", Map.of("path", path));
        String content = "";
        Object encoded = raw.get("content");
        if (encoded != null && !String.valueOf(encoded).isBlank()) {
            try {
                content = new String(Base64.getDecoder().decode(String.valueOf(encoded)), StandardCharsets.UTF_8);
            }
            catch (RuntimeException error) {
                content = String.valueOf(encoded);
            }
        }
        if (content.isBlank()) {
            throw McpanelBusinessException.invalid(path + " 为空或不存在：请先启动一次代理生成配置");
        }
        try {
            return StructuredConfigCodec.parseTree(content, format);
        }
        catch (Exception | LinkageError error) {
            throw McpanelBusinessException.invalid(path + " 解析失败：" + error.getMessage()
                    + (error instanceof LinkageError ? "（面板缺少解析库，请用打包 JAR 部署）" : ""));
        }
    }

    /** servers 值 → 地址：Velocity 允许 "host:port" 字符串或 {address=...} 表；Bungee 为 {address=...}。 */
    private static String serverAddress(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        if (value instanceof Map<?, ?> table && table.get("address") != null) {
            return String.valueOf(table.get("address")).trim();
        }
        return null;
    }

    private static String firstListItem(Object value) {
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
            return String.valueOf(list.get(0));
        }
        return "";
    }

    private static Map<String, Object> newServer(String name, String address) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", name);
        server.put("address", address);
        server.put("external", true);
        return server;
    }

    private List<Map<String, Object>> parseServers(Object raw) {
        List<Map<String, Object>> servers = new ArrayList<>();
        if (raw instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> serverRow) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> server = new LinkedHashMap<>((Map<String, Object>) serverRow);
                    servers.add(server);
                }
            }
        }
        if (servers.size() > MAX_SERVERS) {
            throw McpanelBusinessException.invalid("子服条目最多 " + MAX_SERVERS + " 个");
        }
        if (servers.isEmpty()) {
            throw McpanelBusinessException.invalid("至少保留一个子服条目");
        }
        return servers;
    }

    /** boundInstanceId 是否已被其他代理组占用（排除当前代理组自身）。 */
    private String otherBindingOf(String boundInstanceId, String currentGroupId) {
        for (Map<String, Object> group : loadGroups()) {
            if (String.valueOf(group.get("proxyInstanceId")).equals(currentGroupId)) {
                continue;
            }
            Object rows = group.get("servers");
            if (rows instanceof List<?> serverRows) {
                for (Object row : serverRows) {
                    if (row instanceof Map<?, ?> server && boundInstanceId.equals(
                            server.get("boundInstanceId") == null ? "" : String.valueOf(server.get("boundInstanceId")))) {
                        return String.valueOf(group.get("proxyInstanceId"));
                    }
                }
            }
        }
        return null;
    }

    private List<Map<String, Object>> loadGroups() {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        List<Map<String, Object>> batch;
        do {
            batch = documents.findAll(COLLECTION, page, 200);
            all.addAll(batch);
            page++;
        } while (batch.size() == 200);
        return all;
    }

    private Map<String, McpanelInstance> indexInstances() {
        Map<String, McpanelInstance> index = new HashMap<>();
        for (McpanelInstance instance : instanceRepository.findAll()) {
            index.put(instance.id(), instance);
        }
        return index;
    }

    /** 组文档 → 前端视图：代理/子服名称与状态一并解析（查不到的绑定保留 ID）。 */
    private Map<String, Object> decorateGroup(Map<String, Object> doc) {
        Map<String, Object> view = new LinkedHashMap<>(doc);
        Map<String, McpanelInstance> instances = indexInstances();
        String proxyId = String.valueOf(doc.get("proxyInstanceId"));
        McpanelInstance proxy = instances.get(proxyId);
        view.put("proxyName", proxy == null ? proxyId : proxy.name());
        List<Map<String, Object>> servers = new ArrayList<>();
        Object rows = doc.get("servers");
        if (rows instanceof List<?> serverRows) {
            for (Object row : serverRows) {
                if (row instanceof Map<?, ?> serverRow) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> server = new LinkedHashMap<>((Map<String, Object>) serverRow);
                    String boundId = server.get("boundInstanceId") == null ? "" : String.valueOf(server.get("boundInstanceId"));
                    if (!boundId.isEmpty()) {
                        McpanelInstance bound = instances.get(boundId);
                        server.put("boundName", bound == null ? boundId : bound.name());
                        server.put("boundState", bound == null ? "" : bound.state());
                    }
                    servers.add(server);
                }
            }
        }
        view.put("servers", servers);
        return view;
    }
}
