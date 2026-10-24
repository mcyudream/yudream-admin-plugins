package online.yudream.base.plugin.aioffice.interfaces.controller;

import online.yudream.base.plugin.aioffice.interfaces.http.OfficeHttpFacade;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** AI 模板办公·用户面：我的报销登记与撤回。 */
public class OfficeUserController {

    private final OfficeHttpFacade http;

    public OfficeUserController(OfficeHttpFacade http) {
        this.http = http;
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/finance", permission = OfficePermissions.VIEW)
    public PluginHttpResponse myFinance(PluginHttpRequest request) {
        return http.myFinance(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/finance", permission = OfficePermissions.FINANCE)
    public PluginHttpResponse createFinance(PluginHttpRequest request) {
        return http.myFinance(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/finance/categories", permission = OfficePermissions.VIEW)
    public PluginHttpResponse categories(PluginHttpRequest request) {
        return http.myCategories(request);
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/finance/credential-kinds", permission = OfficePermissions.VIEW)
    public PluginHttpResponse credentialKinds(PluginHttpRequest request) {
        return http.myCredentialKinds(request);
    }

    @PluginHttpEndpoint(method = "POST", path = "/me/finance/upload", permission = OfficePermissions.FINANCE)
    public PluginHttpResponse uploadAttachment(PluginHttpRequest request) {
        return http.myFinanceUpload(request);
    }

    @PluginHttpEndpoint(method = "DELETE", path = "/me/finance/{id}", permission = OfficePermissions.FINANCE)
    public PluginHttpResponse withdrawFinance(PluginHttpRequest request) {
        return http.myFinanceItem(request, segmentAfter(request.path(), "finance"));
    }

    @PluginHttpEndpoint(method = "GET", path = "/me/finance/{id}/attachments/{index}",
            permission = OfficePermissions.VIEW, wrapResult = false)
    public PluginHttpResponse downloadAttachment(PluginHttpRequest request) {
        return http.myFinanceAttachment(request, segmentAfter(request.path(), "finance"),
                intSegmentAfter(request.path(), "attachments"));
    }

    private static String segmentAfter(String path, String marker) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (marker.equals(segments[i])) {
                return URLDecoder.decode(segments[i + 1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int intSegmentAfter(String path, String marker) {
        String value = segmentAfter(path, marker);
        try {
            return value == null ? -1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
