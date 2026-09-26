package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.mcpanel.application.cmd.NodeCreateCmd;
import online.yudream.base.plugin.mcpanel.application.cmd.NodeQueryCmd;
import online.yudream.base.plugin.mcpanel.application.cmd.NodeUpdateCmd;
import online.yudream.base.plugin.mcpanel.application.service.McpanelEnrollService;
import online.yudream.base.plugin.mcpanel.application.service.McpanelNodeAppService;
import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.interfaces.assembler.McpanelWebAssembler;
import online.yudream.base.plugin.mcpanel.interfaces.request.BootstrapRequest;
import online.yudream.base.plugin.mcpanel.interfaces.request.NodeCreateRequest;
import online.yudream.base.plugin.mcpanel.interfaces.request.NodeUpdateRequest;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP 边界：权限校验（principal 判空 + security.hasPermission）、
 * request → command、错误 → 稳定 JSON 错误体；业务全部委托 application。
 * 双保险：注解 permission 已声明，此处再次显式校验并输出统一错误体。
 */
public class McpanelHttpFacade {

    private final McpanelNodeAppService nodeService;
    private final McpanelEnrollService enrollService;
    private final PluginSecurityService security;
    private final McpanelWebAssembler assembler = new McpanelWebAssembler();

    public McpanelHttpFacade(McpanelNodeAppService nodeService,
                             McpanelEnrollService enrollService,
                             PluginSecurityService security) {
        this.nodeService = nodeService;
        this.enrollService = enrollService;
        this.security = security;
    }

    // ---------- admin 查询 ----------

    public PluginHttpResponse page(PluginHttpRequest request) {
        return guarded(request, McpanelPlugin.VIEW_PERMISSION, () -> {
            NodeQueryCmd cmd = new NodeQueryCmd(intQuery(request, "page", 1), intQuery(request, "size", 10),
                    stringQuery(request, "status"), stringQuery(request, "keyword"));
            var result = nodeService.page(cmd);
            return PluginHttpResponse.ok(assembler.toPageRes(
                    result.records().stream().map(assembler::toRes).toList(),
                    result.total(), result.page(), result.size()));
        });
    }

    public PluginHttpResponse detail(PluginHttpRequest request) {
        return guarded(request, McpanelPlugin.VIEW_PERMISSION, () ->
                PluginHttpResponse.ok(assembler.toRes(nodeService.detail(pathSegment(request.path(), 2)))));
    }

    // ---------- admin 变更 ----------

    public PluginHttpResponse create(PluginHttpRequest request) {
        return guarded(request, McpanelPlugin.MANAGE_PERMISSION, () -> {
            NodeCreateRequest body = McpanelJson.read(request.body(), NodeCreateRequest.class);
            NodeCreateCmd cmd = new NodeCreateCmd(body.name(), body.endpoint(), body.tlsMode(),
                    body.pinSha256(), Boolean.TRUE.equals(body.localDevelopment()), body.remark(), body.enabled(),
                    body.portRangeStart(), body.portRangeEnd(), body.sftpHost(),
                    body.accessMode(), body.accessHost());
            return PluginHttpResponse.ok(assembler.toRes(nodeService.create(cmd)));
        });
    }

    public PluginHttpResponse update(PluginHttpRequest request) {
        return guarded(request, McpanelPlugin.MANAGE_PERMISSION, () -> {
            NodeUpdateRequest body = McpanelJson.read(request.body(), NodeUpdateRequest.class);
            NodeUpdateCmd cmd = new NodeUpdateCmd(pathSegment(request.path(), 2), body.name(), body.endpoint(),
                    body.tlsMode(), body.pinSha256(), body.localDevelopment(), body.remark(), body.enabled(),
                    body.portRangeStart(), body.portRangeEnd(), body.sftpHost(),
                    body.accessMode(), body.accessHost());
            return PluginHttpResponse.ok(assembler.toRes(nodeService.update(cmd)));
        });
    }

    public PluginHttpResponse delete(PluginHttpRequest request) {
        return guarded(request, McpanelPlugin.DELETE_PERMISSION, () -> {
            nodeService.delete(pathSegment(request.path(), 2));
            return PluginHttpResponse.ok(Map.of("deleted", true));
        });
    }

    public PluginHttpResponse issueEnrollment(PluginHttpRequest request) {
        return guarded(request, McpanelPlugin.MANAGE_PERMISSION, () ->
                PluginHttpResponse.ok(assembler.toRes(nodeService.issueEnrollment(pathSegment(request.path(), 2)))));
    }

    public PluginHttpResponse reconnect(PluginHttpRequest request) {
        return guarded(request, McpanelPlugin.MANAGE_PERMISSION, () ->
                PluginHttpResponse.ok(assembler.toRes(nodeService.reconnect(pathSegment(request.path(), 2)))));
    }

    // ---------- SSE ----------

    public PluginHttpResponse events(PluginHttpRequest request) {
        return guarded(request, McpanelPlugin.VIEW_PERMISSION, () -> {
            String nodeId = pathSegment(request.path(), 2);
            Long afterEventId = longQuery(request, "lastEventId");
            return new PluginHttpResponse(200,
                    Map.of("Cache-Control", "no-cache", "Connection", "keep-alive", "X-Accel-Buffering", "no"),
                    "text/event-stream",
                    nodeService.stream(nodeId, afterEventId),
                    false);
        });
    }

    // ---------- 机器面 bootstrap（token 门禁，无登录态，rawJson） ----------

    public PluginHttpResponse bootstrap(PluginHttpRequest request) {
        try {
            BootstrapRequest body = McpanelJson.read(request.body(), BootstrapRequest.class);
            var result = enrollService.bootstrap(new online.yudream.base.plugin.mcpanel.application.cmd.BootstrapCmd(
                    body.enrollToken(), body.hostname(), body.agentVersion(), body.caps(), body.tlsCertSha256()));
            return PluginHttpResponse.rawJson(200, assembler.toBootstrapRes(result));
        } catch (McpanelBusinessException error) {
            return error(error);
        } catch (IllegalArgumentException error) {
            return error(new McpanelBusinessException("auth.badToken", 401, "注册请求不合法"));
        }
    }

    // ---------- 内部 ----------

    private PluginHttpResponse guarded(PluginHttpRequest request, String permission,
                                       Supplier<PluginHttpResponse> action) {
        PluginPrincipal principal = request.principal();
        if (principal == null || principal.userId() == null) {
            return error(new McpanelBusinessException("unauthenticated", 401, "请先登录"));
        }
        if (!security.hasPermission(principal, permission)) {
            return error(new McpanelBusinessException("forbidden", 403, "缺少权限：" + permission));
        }
        try {
            return action.get();
        } catch (McpanelBusinessException error) {
            return error(error);
        } catch (IllegalArgumentException error) {
            // 不回显 error.getMessage()：解析异常可能包含请求体片段。
            return error(new McpanelBusinessException("invalid-request", 400, "请求体 JSON 解析失败"));
        }
    }

    private PluginHttpResponse error(McpanelBusinessException error) {
        return PluginHttpResponse.rawJson(error.httpStatus(), Map.of(
                "code", error.code(),
                "message", error.getMessage() == null ? "" : error.getMessage()));
    }

    private String stringQuery(PluginHttpRequest request, String key) {
        List<String> values = request.query().get(key);
        return values == null || values.isEmpty() || values.get(0).isBlank() ? null : values.get(0).trim();
    }

    private Integer intQuery(PluginHttpRequest request, String key, int defaultValue) {
        String value = stringQuery(request, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return defaultValue;
        }
    }

    private Long longQuery(PluginHttpRequest request, String key) {
        String value = stringQuery(request, key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    /**
     * 路径资源 ID：在 collection 段之后取一段，兼容是否有插件 code 前缀。
     * /admin/nodes/{id} 与 mcpanel/admin/nodes/{id} 均返回 {id}。
     */
    private String pathSegment(String path, int index) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        String[] segments = trimmed.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("nodes".equalsIgnoreCase(segments[i]) || "node".equalsIgnoreCase(segments[i])
                    || "instances".equalsIgnoreCase(segments[i])) {
                String candidate = segments[i + 1];
                if (candidate != null && !candidate.isBlank() && !isResourceWord(candidate)) {
                    return URLDecoder.decode(candidate, StandardCharsets.UTF_8);
                }
            }
        }
        if (index >= 0 && index < segments.length) {
            return URLDecoder.decode(segments[index], StandardCharsets.UTF_8);
        }
        return null;
    }

    private static boolean isResourceWord(String value) {
        return switch (value) {
            case "terminal", "files", "ftp", "images", "containers", "events", "tasks",
                    "enrollment", "reconnect", "backups", "output", "command", "start",
                    "stop", "restart", "kill", "server-config", "schedules", "mods", "settings" -> true;
            default -> false;
        };
    }
}
