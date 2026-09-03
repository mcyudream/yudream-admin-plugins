package online.yudream.base.plugin.activityproof.interfaces.controller;

import online.yudream.base.plugin.activityproof.bootstrap.MinecraftActivityProofPlugin;
import online.yudream.base.plugin.activityproof.interfaces.http.ActivityProofHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public class ActivityProofAdminController {
    private final ActivityProofHttpFacade http;

    public ActivityProofAdminController(ActivityProofHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/status", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse status() { return http.status(); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/servers", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse servers() { return http.servers(); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse settings() { return http.settings(); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/templates", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse templates(PluginHttpRequest request) { return http.templates(request); }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) { return http.saveSettings(request); }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/template", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse selectTemplate(PluginHttpRequest request) { return http.selectTemplate(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/departments", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse departments(PluginHttpRequest request) { return http.departments(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/forms", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse forms(PluginHttpRequest request) { return http.forms(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/user-options", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse userOptions(PluginHttpRequest request) { return http.userOptions(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/template-members", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse templateMembers(PluginHttpRequest request) { return http.templateMembers(request); }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/template-members", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse saveTemplateMembers(PluginHttpRequest request) { return http.saveTemplateMembers(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/qq/connections", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse qqConnections() { return http.qqConnections(); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/qq/groups", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse qqGroups(PluginHttpRequest request) { return http.qqGroups(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/activities", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse activities(PluginHttpRequest request) { return http.activities(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/activities", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse createActivity(PluginHttpRequest request) { return http.createActivity(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/activities/{id}", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse activity(PluginHttpRequest request) { return http.activity(request); }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/activities/{id}", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse updateActivity(PluginHttpRequest request) { return http.updateActivity(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/activities/{id}/publish", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse publishActivity(PluginHttpRequest request) { return http.publishActivity(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/activities/{id}/close", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse closeActivity(PluginHttpRequest request) { return http.closeActivity(request); }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/activities/{id}", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse deleteActivity(PluginHttpRequest request) { return http.deleteActivity(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/activities/{id}/participants", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse activityParticipants(PluginHttpRequest request) { return http.activityParticipants(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/activities/{id}/participants", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse addParticipant(PluginHttpRequest request) { return http.addParticipant(request); }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/activities/{id}/participants/{userId}", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse removeParticipant(PluginHttpRequest request) { return http.removeParticipant(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/activities/{id}/participants/{userId}/verify", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse verifyParticipant(PluginHttpRequest request) { return http.verifyParticipant(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/activities/{id}/verify-all", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse verifyAllParticipants(PluginHttpRequest request) { return http.verifyAllParticipants(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/mappings", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse mappings(PluginHttpRequest request) { return http.mappings(request); }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/mappings", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse saveMapping(PluginHttpRequest request) { return http.saveMapping(request); }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/mappings/{id}", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse deleteMapping(PluginHttpRequest request) { return http.deleteMapping(request); }

    @PluginHttpEndpoint(method = "POST", path = "/admin/exports", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse export(PluginHttpRequest request) { return http.export(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/exports", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse exports(PluginHttpRequest request) { return http.exports(request); }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/exports/{id}/stamped-pdf", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse uploadStampedPdf(PluginHttpRequest request) { return http.uploadStampedPdf(request); }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/exports/{id}", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION)
    public PluginHttpResponse deleteExport(PluginHttpRequest request) { return http.deleteExport(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/exports/{id}/download", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION, wrapResult = false)
    public PluginHttpResponse download(PluginHttpRequest request) { return http.download(request); }

    @PluginHttpEndpoint(method = "GET", path = "/admin/exports/{id}/stamped-pdf/download", permission = MinecraftActivityProofPlugin.ACCESS_MANAGE_PERMISSION, wrapResult = false)
    public PluginHttpResponse downloadStampedPdf(PluginHttpRequest request) { return http.downloadStampedPdf(request); }
}
