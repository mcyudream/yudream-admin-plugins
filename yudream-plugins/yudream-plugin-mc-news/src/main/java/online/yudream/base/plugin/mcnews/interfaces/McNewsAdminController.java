package online.yudream.base.plugin.mcnews.interfaces;

import online.yudream.base.plugin.mcnews.bootstrap.McNewsPlugin;
import online.yudream.base.plugin.mcnews.interfaces.support.HttpSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

/** 管理端点（/admin/**，plugin:mc-news:manage）：设置、新闻源、推送目标、新闻动态、推送记录、轮询与选项。 */
public class McNewsAdminController {
    private final McNewsHttpFacade http;

    public McNewsAdminController(McNewsHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse settings() {
        return http.getSettings();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse saveSettings(PluginHttpRequest request) {
        return http.saveSettings(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/sources", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse sources() {
        return http.listSources();
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/sources", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createSource(PluginHttpRequest request) {
        return http.createSource(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/sources/{id}", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updateSource(PluginHttpRequest request) {
        return http.updateSource(request, id(request));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/sources/{id}", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteSource(PluginHttpRequest request) {
        return http.deleteSource(id(request));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/sources/{id}/test", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse testSource(PluginHttpRequest request) {
        return http.testSource(id(request));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/targets", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse targets() {
        return http.listTargets();
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/targets", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse createTarget(PluginHttpRequest request) {
        return http.createTarget(request);
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/targets/{id}", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse updateTarget(PluginHttpRequest request) {
        return http.updateTarget(request, id(request));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/targets/{id}", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteTarget(PluginHttpRequest request) {
        return http.deleteTarget(id(request));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/targets/{id}/test", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse testTarget(PluginHttpRequest request) {
        return http.testTarget(id(request));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/news", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse news(PluginHttpRequest request) {
        return http.pageNews(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/news/{id}", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse deleteNews(PluginHttpRequest request) {
        return http.deleteNews(HttpSupport.segment(request, 2));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/news/clear", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse clearNews() {
        return http.clearNews();
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/news/tombstones/clear", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse clearNewsTombstones() {
        return http.clearNewsTombstones();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/logs", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse logs(PluginHttpRequest request) {
        return http.pageLogs(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/poll", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse triggerPoll() {
        return http.triggerPoll();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/poll/status", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse pollStatus() {
        return http.pollStatus();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/options/connections", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse connections() {
        return http.connectionOptions();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/options/groups", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse groups(PluginHttpRequest request) {
        return http.groupOptions(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/template/variables", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse templateVariables() {
        return http.templateVariables();
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/template/preview", permission = McNewsPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse previewTemplate(PluginHttpRequest request) {
        return http.previewTemplate(request);
    }

    private String id(PluginHttpRequest request) {
        return HttpSupport.segment(request, 2);
    }
}
