package online.yudream.base.plugin.mcwiki.interfaces;

import online.yudream.base.plugin.mcwiki.bootstrap.McWikiPlugin;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public final class McWikiAdminController {
    private final McWikiHttpFacade http;
    public McWikiAdminController(McWikiHttpFacade http) { this.http=http; }
    @PluginHttpEndpoint(method="POST",path="/admin/versions/refresh",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse refresh(PluginHttpRequest r){return http.refreshVersions();}
    @PluginHttpEndpoint(method="GET",path="/admin/versions",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse versions(PluginHttpRequest r){return http.versions(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/versions/{versionId}/import",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse importVersion(PluginHttpRequest r){return http.importVersion(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/versions/{versionId}/publish",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse publish(PluginHttpRequest r){return http.publish(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/versions/{versionId}/unpublish",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse unpublish(PluginHttpRequest r){return http.unpublish(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/renders",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse renders(PluginHttpRequest r){return http.renders();}
    @PluginHttpEndpoint(method="POST",path="/admin/renders/update",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse updateRenders(PluginHttpRequest r){return http.updateRenders();}
    @PluginHttpEndpoint(method="DELETE",path="/admin/versions/{versionId}/data",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse deleteVersionData(PluginHttpRequest r){return http.deleteVersionData(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/jobs",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse jobs(PluginHttpRequest r){return http.jobs(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/jobs/{jobId}",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse job(PluginHttpRequest r){return http.job(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/jobs/{jobId}/events",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse events(PluginHttpRequest r){return http.events(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/jobs/{jobId}/pause",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse pause(PluginHttpRequest r){return http.pause(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/jobs/{jobId}/resume",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse resume(PluginHttpRequest r){return http.resume(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/jobs/{jobId}/cancel",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse cancel(PluginHttpRequest r){return http.cancel(r);}
    @PluginHttpEndpoint(method="DELETE",path="/admin/jobs/{jobId}",permission=McWikiPlugin.MANAGE_PERMISSION) public PluginHttpResponse delete(PluginHttpRequest r){return http.delete(r);}
}
