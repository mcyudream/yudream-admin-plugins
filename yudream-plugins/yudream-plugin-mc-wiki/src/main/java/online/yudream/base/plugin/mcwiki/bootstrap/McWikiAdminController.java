package online.yudream.base.plugin.mcwiki.bootstrap;

import java.util.Map;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

public final class McWikiAdminController {
    @PluginHttpEndpoint(method = "GET", path = "/admin/status", permission = McWikiPlugin.MANAGE_PERMISSION)
    public PluginHttpResponse status(PluginHttpRequest request) {
        String userId = request.principal() == null || request.principal().userId() == null ? null : String.valueOf(request.principal().userId());
        return PluginHttpResponse.ok(Map.of("plugin", McWikiPlugin.CODE, "userId", userId == null ? "" : userId));
    }
}
