package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.mcpanel.interfaces.http.HttpGuards;
import online.yudream.base.plugin.mcpanel.interfaces.http.NodeOpsFacade;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.system.security.PluginPrincipal;
import online.yudream.base.plugin.spi.system.security.PluginSecurityService;

import java.util.Map;

/**
 * 节点级能力端点（M2 第二批）：节点终端、节点文件、节点 SFTP。
 * 全部为对节点协议方法的受权限门禁的代理；事件流走 SSE。
 */
public class McpanelNodeOpsController {

    private final NodeOpsFacade facade;
    private final PluginSecurityService security;

    public McpanelNodeOpsController(NodeOpsFacade facade, PluginSecurityService security) {
        this.facade = facade;
        this.security = security;
    }

    // ---------- 终端（plugin:mcpanel:use） ----------

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/terminal/open", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse terminalOpen(PluginHttpRequest request) {
        return guardedUse(request, () -> PluginHttpResponse.ok(
                facade.terminalOpen(actor(request), nodeId(request), body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/terminal/input", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse terminalInput(PluginHttpRequest request) {
        return guardedUse(request, () -> PluginHttpResponse.ok(
                facade.terminalInput(actor(request), nodeId(request), body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/terminal/close", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse terminalClose(PluginHttpRequest request) {
        return guardedUse(request, () -> PluginHttpResponse.ok(
                facade.terminalClose(actor(request), nodeId(request), body(request))));
    }

    /** 真实系统命令补全（节点 0.4.0+）：body 含 prefix（单个命令词）与可选 limit。 */
    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/terminal/complete", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse terminalComplete(PluginHttpRequest request) {
        return guardedUse(request, () -> PluginHttpResponse.ok(
                facade.terminalComplete(nodeId(request), body(request))));
    }

    /** 节点终端输出 SSE（evt node.terminal.output，按 terminalId 过滤，须 open 后传入）。 */
    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "GET", path = "/admin/nodes/{nodeId}/terminal/events", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse terminalEvents(PluginHttpRequest request) {
        return guardedUse(request, () -> {
            String terminalId = query(request, "terminalId");
            PluginSseStream stream = facade.terminalEvents(nodeId(request), terminalId);
            return new PluginHttpResponse(200,
                    Map.of("Cache-Control", "no-cache", "Connection", "keep-alive", "X-Accel-Buffering", "no"),
                    "text/event-stream", stream, false);
        });
    }

    // ---------- 节点文件（view 读 / manage 写） ----------

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/files/list", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse fileList(PluginHttpRequest request) {
        return guardedView(request, () -> PluginHttpResponse.ok(
                facade.nodeFile(nodeId(request), "list", body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/files/read", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse fileRead(PluginHttpRequest request) {
        return guardedView(request, () -> PluginHttpResponse.ok(
                facade.nodeFile(nodeId(request), "read", body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/files/write", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse fileWrite(PluginHttpRequest request) {
        return guardedManage(request, () -> PluginHttpResponse.ok(
                facade.nodeFile(nodeId(request), "write", body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/files/mkdir", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse fileMkdir(PluginHttpRequest request) {
        return guardedManage(request, () -> PluginHttpResponse.ok(
                facade.nodeFile(nodeId(request), "mkdir", body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/files/rename", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse fileRename(PluginHttpRequest request) {
        return guardedManage(request, () -> PluginHttpResponse.ok(
                facade.nodeFile(nodeId(request), "rename", body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/files/delete", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse fileDelete(PluginHttpRequest request) {
        return guardedManage(request, () -> PluginHttpResponse.ok(
                facade.nodeFile(nodeId(request), "delete", body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/files/zip", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse fileZip(PluginHttpRequest request) {
        return guardedManage(request, () -> PluginHttpResponse.ok(
                facade.nodeFile(nodeId(request), "zip", body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/files/download", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse fileDownload(PluginHttpRequest request) {
        return guardedView(request, () -> PluginHttpResponse.ok(
                facade.nodeFile(nodeId(request), "download.chunk", body(request))));
    }

    // ---------- 节点 SFTP（manage） ----------

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/ftp/open", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse ftpOpen(PluginHttpRequest request) {
        return guardedManage(request, () -> PluginHttpResponse.ok(
                facade.nodeFtpOpen(actor(request), nodeId(request), body(request))));
    }

    @online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint(
            method = "POST", path = "/admin/nodes/{nodeId}/ftp/close", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse ftpClose(PluginHttpRequest request) {
        return guardedManage(request, () -> PluginHttpResponse.ok(
                facade.nodeFtpClose(actor(request), nodeId(request), body(request))));
    }

    // ---------- 内部 ----------

    /**
     * 从插件相对路径解析 nodeId。
     * 兼容：admin/nodes/{id}/terminal/open、mcpanel/admin/nodes/{id}/files/list 等；
     * 固定下标会把 terminal/files 当成 id（报「节点不存在」）。
     */
    private String nodeId(PluginHttpRequest request) {
        String path = request.path() == null ? "" : request.path();
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (!"nodes".equalsIgnoreCase(segments[i]) && !"node".equalsIgnoreCase(segments[i])) {
                continue;
            }
            String candidate = segments[i + 1];
            if (candidate == null || candidate.isBlank() || isResourceSegment(candidate)) {
                continue;
            }
            return java.net.URLDecoder.decode(candidate, java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    private static boolean isResourceSegment(String value) {
        return switch (value) {
            case "terminal", "files", "ftp", "images", "containers", "events", "tasks",
                    "enrollment", "reconnect", "stats", "settings" -> true;
            default -> false;
        };
    }

    private String actor(PluginHttpRequest request) {
        PluginPrincipal principal = request.principal();
        return principal == null || principal.userId() == null ? "anonymous" : "user:" + principal.userId();
    }

    private Map<String, Object> body(PluginHttpRequest request) {
        return McpanelJson.mapper().convertValue(
                McpanelJson.readMap(request.body()).node(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    private String query(PluginHttpRequest request, String key) {
        var values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private PluginHttpResponse guardedView(PluginHttpRequest request, java.util.function.Supplier<PluginHttpResponse> action) {
        return HttpGuards.guarded(request, security, McpanelPlugin.VIEW_PERMISSION, action);
    }

    private PluginHttpResponse guardedUse(PluginHttpRequest request, java.util.function.Supplier<PluginHttpResponse> action) {
        return HttpGuards.guarded(request, security, McpanelPlugin.USE_PERMISSION, action);
    }

    private PluginHttpResponse guardedManage(PluginHttpRequest request, java.util.function.Supplier<PluginHttpResponse> action) {
        return HttpGuards.guarded(request, security, McpanelPlugin.MANAGE_PERMISSION, action);
    }
}
