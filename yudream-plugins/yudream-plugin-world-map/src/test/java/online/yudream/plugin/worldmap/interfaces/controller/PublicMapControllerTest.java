package online.yudream.plugin.worldmap.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicMapControllerTest {

    @Test
    void registersTheGenerationScopedLowresCoverageEndpoint() throws Exception {
        Method method = PublicMapController.class.getDeclaredMethod("generationBlueMapLowresIndex", PluginHttpRequest.class);

        PluginHttpEndpoint endpoint = method.getAnnotation(PluginHttpEndpoint.class);

        assertNotNull(endpoint);
        assertEquals("GET", endpoint.method());
        assertEquals("/maps/{mapId}/generations/{generationId}/lowres-index.json", endpoint.path());
    }

    @Test
    void cachesGenerationUrlsImmutablyButKeepsCompatibilityUrlsRevalidatable() {
        assertEquals("public, max-age=31536000, immutable",
                PublicMapController.cacheHeaders(false, true).get("Cache-Control"));
        assertEquals("public, max-age=60, must-revalidate",
                PublicMapController.cacheHeaders(false, false).get("Cache-Control"));
        assertEquals("gzip", PublicMapController.cacheHeaders(true, true).get("Content-Encoding"));
        assertTrue(PublicMapController.cacheHeaders(true, false).containsKey("Cache-Control"));
    }
}
