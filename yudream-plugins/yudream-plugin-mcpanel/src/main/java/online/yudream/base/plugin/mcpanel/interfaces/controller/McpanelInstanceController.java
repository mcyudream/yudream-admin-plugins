package online.yudream.base.plugin.mcpanel.interfaces.controller;

import online.yudream.base.plugin.mcpanel.bootstrap.McpanelPlugin;
import online.yudream.base.plugin.mcpanel.interfaces.http.McpanelInstanceFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 实例管理端点（/admin/instances/**）。 */
public class McpanelInstanceController {

    private final McpanelInstanceFacade facade;

    public McpanelInstanceController(McpanelInstanceFacade facade) {
        this.facade = facade;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse page(PluginHttpRequest request) {
        return facade.page(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse create(PluginHttpRequest request) {
        return facade.create(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse detail(PluginHttpRequest request) {
        return facade.detail(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/instances/{instanceId}", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse update(PluginHttpRequest request) {
        return facade.update(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/instances/{instanceId}/event-task", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveEventTask(PluginHttpRequest request) {
        return facade.eventTaskSave(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/instances/{instanceId}", permission = McpanelPlugin.DELETE_PERMISSION)
    public PluginHttpResponse delete(PluginHttpRequest request) {
        return facade.delete(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/start", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse start(PluginHttpRequest request) {
        return facade.action(request, "start");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/stop", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse stop(PluginHttpRequest request) {
        return facade.action(request, "stop");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/restart", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse restart(PluginHttpRequest request) {
        return facade.action(request, "restart");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/kill", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse kill(PluginHttpRequest request) {
        return facade.action(request, "kill");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/command", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse command(PluginHttpRequest request) {
        return facade.command(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/output", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse output(PluginHttpRequest request) {
        return facade.output(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/tps-probe", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse tpsProbe(PluginHttpRequest request) {
        return facade.tpsProbe(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/players", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse players(PluginHttpRequest request) {
        return facade.players(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/files", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse listFiles(PluginHttpRequest request) {
        return facade.files(request, "list");
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/files/content", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse readFile(PluginHttpRequest request) {
        return facade.files(request, "read");
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/files/download", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse downloadFile(PluginHttpRequest request) {
        return facade.files(request, "download");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/files", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse writeFile(PluginHttpRequest request) {
        return facade.files(request, "write");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/files/mkdir", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse mkdir(PluginHttpRequest request) {
        return facade.files(request, "mkdir");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/files/rename", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse rename(PluginHttpRequest request) {
        return facade.files(request, "rename");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/files/delete", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteFile(PluginHttpRequest request) {
        return facade.files(request, "delete");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/files/upload", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse upload(PluginHttpRequest request) {
        return facade.upload(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/backups", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createBackup(PluginHttpRequest request) {
        return facade.backup(request, "create");
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/backups", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse listBackups(PluginHttpRequest request) {
        return facade.backup(request, "list");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/backups/trigger", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse triggerBackup(PluginHttpRequest request) {
        return facade.backupTrigger(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/backup-policy", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse backupPolicy(PluginHttpRequest request) {
        return facade.backupPolicy(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/instances/{instanceId}/backup-policy", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveBackupPolicy(PluginHttpRequest request) {
        return facade.backupPolicySave(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/backups/restore", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse restoreBackup(PluginHttpRequest request) {
        return facade.backup(request, "restore");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/backups/delete", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteBackup(PluginHttpRequest request) {
        return facade.backup(request, "delete");
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/nodes/{nodeId}/images/pull", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse pullImage(PluginHttpRequest request) {
        return facade.imagePull(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/nodes/{nodeId}/images", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse imageList(PluginHttpRequest request) {
        return facade.imageList(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/nodes/{nodeId}/containers", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse nodeContainers(PluginHttpRequest request) {
        return facade.nodeContainers(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/nodes/{nodeId}/images", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse imageRemove(PluginHttpRequest request) {
        return facade.imageRemove(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/nodes/{nodeId}/tasks", permission = McpanelPlugin.VIEW_PERMISSION)
    public PluginHttpResponse task(PluginHttpRequest request) {
        return facade.task(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/files/zip", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse fileZip(PluginHttpRequest request) {
        return facade.fileZip(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/files/unzip", permission = McpanelPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse fileUnzip(PluginHttpRequest request) {
        return facade.fileUnzip(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/ftp/open", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse ftpOpen(PluginHttpRequest request) {
        return facade.ftpOpen(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/ftp/close", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse ftpClose(PluginHttpRequest request) {
        return facade.ftpClose(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/output/subscribe", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse outputSubscribe(PluginHttpRequest request) {
        return facade.outputSubscribe(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/instances/{instanceId}/output/unsubscribe", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse outputUnsubscribe(PluginHttpRequest request) {
        return facade.outputUnsubscribe(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/instances/{instanceId}/output/events", permission = McpanelPlugin.USE_PERMISSION)
    public PluginHttpResponse outputEvents(PluginHttpRequest request) {
        return facade.outputEvents(request);
    }
}
