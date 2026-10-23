package online.yudream.base.plugin.cas.infrastructure.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSupportTest {

    public record Sample(String protocol, Boolean enabled, String clientSecret) {
    }

    @Test
    void ignoresUnknownFieldsFromSettingsForm() {
        String body = """
                {
                  "enabled": true,
                  "protocol": "CAS",
                  "clientSecret": "keep-me",
                  "clientSecretConfigured": true,
                  "ready": false
                }
                """;
        Sample parsed = JsonSupport.read(body, Sample.class);
        assertTrue(parsed.enabled());
        assertEquals("CAS", parsed.protocol());
        assertEquals("keep-me", parsed.clientSecret());
    }

    @Test
    void blankBodyYieldsEmptyObject() {
        Sample parsed = JsonSupport.read("", Sample.class);
        assertNull(parsed.protocol());
        assertNull(parsed.enabled());
    }
}
