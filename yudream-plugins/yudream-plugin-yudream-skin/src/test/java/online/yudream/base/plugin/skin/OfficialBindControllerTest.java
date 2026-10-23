package online.yudream.base.plugin.skin;

import online.yudream.base.plugin.skin.application.service.OfficialBindService;
import online.yudream.base.plugin.skin.interfaces.controller.OfficialBindController;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 官方绑定 HTTP 边界：OAuth callback 不泄露绑定数据，官方会话响应禁止缓存。 */
class OfficialBindControllerTest {

    @Test
    void cancelledCallbackReturnsMessagePage() {
        OfficialBindController controller = new OfficialBindController(null);
        PluginHttpResponse response = controller.callback(new PluginHttpRequest("GET", "/official-bind/callback",
                Map.of(), Map.of("error", List.of("access_denied")), "", null));

        assertEquals(400, response.status());
        assertEquals("text/html;charset=UTF-8", response.contentType());
        assertEquals("no-store, max-age=0", response.headers().get("Cache-Control"));
        assertTrue(String.valueOf(response.body()).contains("yudream-official-bind"));
        assertFalse(String.valueOf(response.body()).contains("access_denied"));
    }
}
