package online.yudream.base.plugin.union.interfaces.controller;

import online.yudream.base.plugin.union.interfaces.http.UnionHttpFacade;
import online.yudream.base.plugin.union.interfaces.support.PathSupport;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.util.Map;

/** 联合认证管理面（/admin/**：站点目录、封禁账本、审计与互通自检）。 */
public class UnionAdminController {

    private final UnionHttpFacade facade;

    public UnionAdminController(UnionHttpFacade facade) {
        this.facade = facade;
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/settings", permission = "plugin:yudream-union:view")
    public Object settings(PluginHttpRequest request) {
        return facade.adminSettings();
    }

    @PluginHttpEndpoint(method = "PUT", path = "/admin/settings", permission = "plugin:yudream-union:manage")
    public Object saveSettings(PluginHttpRequest request) {
        return facade.saveSettings(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/enroll-tokens", permission = "plugin:yudream-union:manage")
    public Object issueEnrollToken(PluginHttpRequest request) {
        return facade.issueEnrollToken(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/peers", permission = "plugin:yudream-union:view")
    public Object pagePeers(PluginHttpRequest request) {
        int page = intParam(request, "page", 1);
        int size = intParam(request, "pageSize", 20);
        return facade.pagePeers(page, size);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/peers", permission = "plugin:yudream-union:manage")
    public Object enrollPeer(PluginHttpRequest request) {
        return facade.enrollPeer(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/peers/{siteCode}/status", permission = "plugin:yudream-union:manage")
    public Object setPeerStatus(PluginHttpRequest request) {
        return facade.setPeerStatus(request, PathSupport.segment(request.path(), 2));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/peers/{siteCode}/refresh", permission = "plugin:yudream-union:manage")
    public Object refreshPeer(PluginHttpRequest request) {
        return facade.refreshPeer(PathSupport.segment(request.path(), 2));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/peers/{siteCode}/selftest", permission = "plugin:yudream-union:view")
    public Object selfTestPeer(PluginHttpRequest request) {
        return facade.selfTestPeer(PathSupport.segment(request.path(), 2));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/peers/{siteCode}", permission = "plugin:yudream-union:manage")
    public PluginHttpResponse deletePeer(PluginHttpRequest request) {
        facade.deletePeer(PathSupport.segment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/bans", permission = "plugin:yudream-union:view")
    public Object pageBans(PluginHttpRequest request) {
        return facade.pageBans(intParam(request, "page", 1), intParam(request, "pageSize", 20));
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/bans", permission = "plugin:yudream-union:manage")
    public Object addBan(PluginHttpRequest request) {
        return facade.addBan(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/admin/bans/pull", permission = "plugin:yudream-union:manage")
    public Object pullBans(PluginHttpRequest request) {
        return facade.pullBans();
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/bans/{name}", permission = "plugin:yudream-union:revoke")
    public PluginHttpResponse liftBan(PluginHttpRequest request) {
        facade.liftBan(PathSupport.segment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("lifted", true));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/audit", permission = "plugin:yudream-union:view")
    public Object pageAudit(PluginHttpRequest request) {
        return facade.pageAudit(intParam(request, "page", 1), intParam(request, "pageSize", 20),
                firstQuery(request, "keyword"), firstQuery(request, "sortBy"), firstQuery(request, "order"));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/audit/{id}", permission = "plugin:yudream-union:manage")
    public PluginHttpResponse deleteAudit(PluginHttpRequest request) {
        facade.deleteAudit(PathSupport.segment(request.path(), 2));
        return PluginHttpResponse.ok(Map.of("deleted", true));
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/admin/audit", permission = "plugin:yudream-union:manage")
    public PluginHttpResponse clearAudit(PluginHttpRequest request) {
        facade.clearAudit();
        return PluginHttpResponse.ok(Map.of("cleared", true));
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/federation/stats", permission = "plugin:yudream-union:view")
    public Object federationStats(PluginHttpRequest request) {
        return facade.federationStats();
    }

    @PluginHttpEndpoint(method = "GET", path = "/admin/federation/log", permission = "plugin:yudream-union:view")
    public Object federationLog(PluginHttpRequest request) {
        return facade.federationLog(
                intParam(request, "page", 1),
                intParam(request, "pageSize", 20),
                firstQuery(request, "site"),
                firstQuery(request, "name"),
                firstQuery(request, "sortBy"),
                firstQuery(request, "order"));
    }

    private String firstQuery(PluginHttpRequest request, String key) {
        var values = request.query().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private int intParam(PluginHttpRequest request, String key, int fallback) {
        var values = request.query().get(key);
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(values.get(0));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
