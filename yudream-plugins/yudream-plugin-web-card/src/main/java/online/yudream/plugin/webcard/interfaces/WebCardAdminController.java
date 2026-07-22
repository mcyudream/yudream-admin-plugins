package online.yudream.plugin.webcard.interfaces;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.plugin.webcard.bootstrap.WebCardPlugin;

public final class WebCardAdminController {
    private final WebCardHttpFacade h; public WebCardAdminController(WebCardHttpFacade h){this.h=h;}
    @PluginHttpEndpoint(method="GET",path="/admin/sites",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse sites(PluginHttpRequest r){return h.sites(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/sites/{id}",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse site(PluginHttpRequest r){return h.site(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/sites",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse saveSite(PluginHttpRequest r){return h.saveSite(r);}
    @PluginHttpEndpoint(method="DELETE",path="/admin/sites/{id}",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse deleteSite(PluginHttpRequest r){return h.deleteSite(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/sites/{id}/parse-rules",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse rules(PluginHttpRequest r){return h.rules(r);}
    @PluginHttpEndpoint(method="PUT",path="/admin/sites/{id}/parse-rules",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse saveRules(PluginHttpRequest r){return h.saveRules(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/sites/{id}/test-fetch",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse testFetch(PluginHttpRequest r){return h.testFetch(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/sites/{id}/test-parse",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse testParse(PluginHttpRequest r){return h.testParse(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/link-preview",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse previewUrl(PluginHttpRequest r){return h.previewUrl(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/template-draft-preview",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse previewDraftUrl(PluginHttpRequest r){return h.previewDraftUrl(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/templates",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse templates(PluginHttpRequest r){return h.templates(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/templates/{id}",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse template(PluginHttpRequest r){return h.template(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/templates",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse saveTemplate(PluginHttpRequest r){return h.saveTemplate(r);}
    @PluginHttpEndpoint(method="DELETE",path="/admin/templates/{id}",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse deleteTemplate(PluginHttpRequest r){return h.deleteTemplate(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/template-versions",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse saveVersion(PluginHttpRequest r){return h.saveVersion(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/template-versions/{id}",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse version(PluginHttpRequest r){return h.version(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/templates/{id}/versions",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse versions(PluginHttpRequest r){return h.versions(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/template-versions/{id}/preview",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse preview(PluginHttpRequest r){return h.preview(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/templates/{id}/publish",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse publish(PluginHttpRequest r){return h.publish(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/templates/{id}/rollback",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse rollback(PluginHttpRequest r){return h.rollback(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/group-bindings",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse bindings(PluginHttpRequest r){return h.bindings(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/group-bindings",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse saveBinding(PluginHttpRequest r){return h.saveBinding(r);}
    @PluginHttpEndpoint(method="DELETE",path="/admin/group-bindings/{id}",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse deleteBinding(PluginHttpRequest r){return h.deleteBinding(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/crawl-jobs",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse jobs(PluginHttpRequest r){return h.jobs(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/crawl-jobs",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse saveJob(PluginHttpRequest r){return h.saveJob(r);}
    @PluginHttpEndpoint(method="DELETE",path="/admin/crawl-jobs/{id}",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse deleteJob(PluginHttpRequest r){return h.deleteJob(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/deliveries",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse deliveries(PluginHttpRequest r){return h.deliveries(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/content-records",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse contents(PluginHttpRequest r){return h.contents(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/deliveries/{id}/retry",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse retry(PluginHttpRequest r){return h.retry(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/agent-sessions",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse sessions(PluginHttpRequest r){return h.sessions(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/agent-sessions",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse createSession(PluginHttpRequest r){return h.createSession(r);}
    @PluginHttpEndpoint(method="DELETE",path="/admin/agent-sessions/{id}",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse deleteSession(PluginHttpRequest r){return h.deleteSession(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/agent-sessions/{id}/messages",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse agentMessage(PluginHttpRequest r){return h.agentMessage(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/agent-sessions/{id}/messages/stream",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse agentMessageStream(PluginHttpRequest r){return h.agentMessageStream(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/agent-message-streams/{id}/events",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse agentMessageEvents(PluginHttpRequest r){return h.agentMessageEvents(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/agent-proposals",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse proposals(PluginHttpRequest r){return h.proposals(r);}
    @PluginHttpEndpoint(method="PUT",path="/admin/agent-proposals/{id}",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse updateProposal(PluginHttpRequest r){return h.updateProposal(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/agent-proposals/{id}/apply",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse applyProposal(PluginHttpRequest r){return h.applyProposal(r);}
    @PluginHttpEndpoint(method="POST",path="/admin/agent-proposals/{id}/reject",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse rejectProposal(PluginHttpRequest r){return h.rejectProposal(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/options/connections",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse connections(){return h.connections();}
    @PluginHttpEndpoint(method="GET",path="/admin/options/groups",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse groups(PluginHttpRequest r){return h.groups(r);}
    @PluginHttpEndpoint(method="GET",path="/admin/options/ai-agents",permission=WebCardPlugin.MANAGE_PERMISSION) public PluginHttpResponse aiAgents(){return h.aiAgents();}
}
