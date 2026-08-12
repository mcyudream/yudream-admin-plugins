package online.yudream.plugin.webcard.interfaces;

import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.plugin.webcard.application.AgentAuthoringService;
import online.yudream.plugin.webcard.application.WebCardApplicationService;
import online.yudream.plugin.webcard.domain.WebCardModels.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class WebCardHttpFacade {
    public record SiteRequest(Site site, Map<String, String> headers) { }
    public record UrlRequest(String url) { }
    public record RouteRuleTestRequest(String url, SiteRouteRule rule) { }
    public record PreviewRequest(Map<String, Object> fixture) { }
    public record DraftPreviewRequest(String siteId, String url, TemplateVersion version, Site site, ParseRules rules) { }
    public record PublishRequest(String versionId) { }
    public record AgentMessageRequest(String message) { }
    public record ProposalUpdateRequest(Map<String, Object> plan) { }
    private final WebCardApplicationService app; private final AgentAuthoringService agents; private final FrameworkServices framework;
    public WebCardHttpFacade(WebCardApplicationService app, AgentAuthoringService agents, FrameworkServices framework) { this.app = app; this.agents = agents; this.framework = framework; }
    public PluginHttpResponse sites(PluginHttpRequest r) { return PluginHttpResponse.ok(app.sites(number(r,"page",1), number(r,"size",10))); }
    public PluginHttpResponse site(PluginHttpRequest r) { String id = path(r,2); var body = new java.util.LinkedHashMap<String,Object>(); body.put("site", app.site(id).orElse(null)); body.put("headers", app.maskedHeaders(id)); return PluginHttpResponse.ok(body); }
    public PluginHttpResponse saveSite(PluginHttpRequest r) { SiteRequest request = JsonSupport.read(r.body(), SiteRequest.class); return PluginHttpResponse.ok(app.saveSite(request.site(), request.headers())); }
    public PluginHttpResponse deleteSite(PluginHttpRequest r) { app.deleteSite(path(r,2)); return PluginHttpResponse.ok(Map.of("deleted",true)); }
    public PluginHttpResponse rules(PluginHttpRequest r) { return PluginHttpResponse.ok(app.rules(path(r,2)).orElse(null)); }
    public PluginHttpResponse saveRules(PluginHttpRequest r) { return PluginHttpResponse.ok(app.saveRules(JsonSupport.read(r.body(), ParseRules.class))); }
    public PluginHttpResponse routeRules(PluginHttpRequest r) { return PluginHttpResponse.ok(app.routeRules(path(r,2))); }
    public PluginHttpResponse saveRouteRule(PluginHttpRequest r) { return PluginHttpResponse.ok(app.saveRouteRule(JsonSupport.read(r.body(), SiteRouteRule.class))); }
    public PluginHttpResponse deleteRouteRule(PluginHttpRequest r) { app.deleteRouteRule(path(r,2)); return PluginHttpResponse.ok(Map.of("deleted",true)); }
    public PluginHttpResponse testFetch(PluginHttpRequest r) { UrlRequest request=JsonSupport.read(r.body(),UrlRequest.class); return PluginHttpResponse.ok(app.testFetch(path(r,2),request.url())); }
    public PluginHttpResponse testParse(PluginHttpRequest r) { UrlRequest request=JsonSupport.read(r.body(),UrlRequest.class); return PluginHttpResponse.ok(app.testParse(path(r,2),request.url())); }
    public PluginHttpResponse testRouteRule(PluginHttpRequest r) { RouteRuleTestRequest request=JsonSupport.read(r.body(),RouteRuleTestRequest.class); return PluginHttpResponse.ok(app.testRouteRule(path(r,2),request.url(),request.rule())); }
    public PluginHttpResponse previewUrl(PluginHttpRequest r) { return PluginHttpResponse.ok(app.previewUrl(JsonSupport.read(r.body(), UrlRequest.class).url())); }
    public PluginHttpResponse previewDraftUrl(PluginHttpRequest r) { DraftPreviewRequest request=JsonSupport.read(r.body(),DraftPreviewRequest.class); return PluginHttpResponse.ok(app.previewDraftUrl(request.siteId(),request.url(),request.version(),request.site(),request.rules())); }
    public PluginHttpResponse templates(PluginHttpRequest r) { return PluginHttpResponse.ok(app.templates(number(r,"page",1),number(r,"size",10))); }
    public PluginHttpResponse template(PluginHttpRequest r) { return PluginHttpResponse.ok(app.template(path(r,2)).orElse(null)); }
    public PluginHttpResponse saveTemplate(PluginHttpRequest r) { return PluginHttpResponse.ok(app.saveTemplate(JsonSupport.read(r.body(),Template.class))); }
    public PluginHttpResponse deleteTemplate(PluginHttpRequest r) { app.deleteTemplate(path(r,2)); return PluginHttpResponse.ok(Map.of("deleted",true)); }
    public PluginHttpResponse saveVersion(PluginHttpRequest r) { return PluginHttpResponse.ok(app.saveVersion(JsonSupport.read(r.body(),TemplateVersion.class))); }
    public PluginHttpResponse version(PluginHttpRequest r) { return PluginHttpResponse.ok(app.version(path(r,2)).orElse(null)); }
    public PluginHttpResponse versions(PluginHttpRequest r) { return PluginHttpResponse.ok(app.versions(path(r,2),number(r,"page",1),number(r,"size",10))); }
    public PluginHttpResponse preview(PluginHttpRequest r) { PreviewRequest request=JsonSupport.read(r.body(),PreviewRequest.class); return PluginHttpResponse.ok(app.preview(path(r,2),request.fixture())); }
    public PluginHttpResponse publish(PluginHttpRequest r) { PublishRequest request=JsonSupport.read(r.body(),PublishRequest.class); return PluginHttpResponse.ok(app.publish(path(r,2),request.versionId())); }
    public PluginHttpResponse rollback(PluginHttpRequest r) { PublishRequest request=JsonSupport.read(r.body(),PublishRequest.class); return PluginHttpResponse.ok(app.rollback(path(r,2),request.versionId())); }
    public PluginHttpResponse bindings(PluginHttpRequest r) { return PluginHttpResponse.ok(app.bindings(number(r,"page",1),number(r,"size",10))); }
    public PluginHttpResponse saveBinding(PluginHttpRequest r) { return PluginHttpResponse.ok(app.saveBinding(JsonSupport.read(r.body(),GroupBinding.class))); }
    public PluginHttpResponse deleteBinding(PluginHttpRequest r) { app.deleteBinding(path(r,2)); return PluginHttpResponse.ok(Map.of("deleted",true)); }
    public PluginHttpResponse jobs(PluginHttpRequest r) { return PluginHttpResponse.ok(app.jobs(number(r,"page",1),number(r,"size",10))); }
    public PluginHttpResponse saveJob(PluginHttpRequest r) { return PluginHttpResponse.ok(app.saveJob(JsonSupport.read(r.body(),CrawlJob.class))); }
    public PluginHttpResponse deleteJob(PluginHttpRequest r) { app.deleteJob(path(r,2)); return PluginHttpResponse.ok(Map.of("deleted",true)); }
    public PluginHttpResponse deliveries(PluginHttpRequest r) { return PluginHttpResponse.ok(app.deliveries(number(r,"page",1),number(r,"size",10))); }
    public PluginHttpResponse contents(PluginHttpRequest r) { return PluginHttpResponse.ok(app.contents(number(r,"page",1),number(r,"size",10))); }
    public PluginHttpResponse retry(PluginHttpRequest r) { return PluginHttpResponse.ok(app.retry(path(r,2))); }
    public PluginHttpResponse sessions(PluginHttpRequest r) { return PluginHttpResponse.ok(agents.sessions(number(r,"page",1),number(r,"size",10))); }
    public PluginHttpResponse createSession(PluginHttpRequest r) { return PluginHttpResponse.ok(agents.create(JsonSupport.read(r.body(),AgentSession.class))); }
    public PluginHttpResponse deleteSession(PluginHttpRequest r) { agents.delete(path(r,2)); return PluginHttpResponse.ok(Map.of("deleted",true)); }
    public PluginHttpResponse agentMessage(PluginHttpRequest r) { AgentMessageRequest request=JsonSupport.read(r.body(),AgentMessageRequest.class); return PluginHttpResponse.ok(agents.message(path(r,2),request.message())); }
    public PluginHttpResponse agentMessageStream(PluginHttpRequest r) { AgentMessageRequest request=JsonSupport.read(r.body(),AgentMessageRequest.class); return PluginHttpResponse.json(202, Map.of("streamId", agents.startMessage(path(r,2), request.message()))); }
    public PluginHttpResponse agentMessageEvents(PluginHttpRequest r) { return new PluginHttpResponse(200, Map.of("Cache-Control", "no-cache", "Connection", "keep-alive"), "text/event-stream", agents.stream(path(r,2)), false); }
    public PluginHttpResponse proposals(PluginHttpRequest r) { return PluginHttpResponse.ok(agents.proposals(number(r,"page",1),number(r,"size",10))); }
    public PluginHttpResponse updateProposal(PluginHttpRequest r) { ProposalUpdateRequest request=JsonSupport.read(r.body(), ProposalUpdateRequest.class); return PluginHttpResponse.ok(agents.update(path(r,2), request.plan())); }
    public PluginHttpResponse applyProposal(PluginHttpRequest r) { return PluginHttpResponse.ok(agents.apply(path(r,2))); }
    public PluginHttpResponse rejectProposal(PluginHttpRequest r) { return PluginHttpResponse.ok(agents.reject(path(r,2))); }
    public PluginHttpResponse connections() { return PluginHttpResponse.ok(framework.messaging().connections()); }
    public PluginHttpResponse groups(PluginHttpRequest r) { return PluginHttpResponse.ok(framework.messaging().groups(query(r,"connectionId"))); }
    public PluginHttpResponse aiAgents() { return PluginHttpResponse.ok(framework.ai().agents().stream().map(value -> Map.of("id", value.code(), "name", value.name())).toList()); }
    private int number(PluginHttpRequest r,String key,int fallback){try{return Integer.parseInt(query(r,key));}catch(Exception e){return fallback;}}
    private String query(PluginHttpRequest r,String key){var values=r.query().get(key);return values==null||values.isEmpty()?null:values.getFirst();}
    private String path(PluginHttpRequest r,int index){String[] values=(r.path()==null?"":r.path()).replaceFirst("^/+","").split("/");return index<values.length?URLDecoder.decode(values[index], StandardCharsets.UTF_8):null;}
}
