package online.yudream.base.plugin.mcpanel.application.service;

import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileCharsetCodecTest {

    private static String b64Utf8(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void utf8OrAbsentPassesThroughUntouched() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", b64Utf8("中文"));
        payload.put("encoding", "base64");
        FileCharsetCodec.applyCharset(payload);
        assertEquals(b64Utf8("中文"), payload.get("content"));
        payload.put("charset", "utf-8");
        FileCharsetCodec.applyCharset(payload);
        assertEquals(b64Utf8("中文"), payload.get("content"));
        assertEquals("base64", payload.get("encoding"));
    }

    @Test
    void gbkTranscodesToLegacyBytes() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", b64Utf8("中文配置"));
        payload.put("charset", "gbk");
        FileCharsetCodec.applyCharset(payload);
        byte[] bytes = Base64.getDecoder().decode((String) payload.get("content"));
        assertEquals("中文配置", new String(bytes, Charset.forName("gbk")));
        assertEquals(8, bytes.length);
        assertEquals("base64", payload.get("encoding"));
        assertEquals(null, payload.get("charset"));
    }

    @Test
    void unknownCharsetAndBadBase64AreRejected() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", b64Utf8("x"));
        payload.put("charset", "utf-32");
        assertThrows(McpanelBusinessException.class, () -> FileCharsetCodec.applyCharset(payload));

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("content", "!!!not-base64!!!");
        bad.put("charset", "gbk");
        assertThrows(McpanelBusinessException.class, () -> FileCharsetCodec.applyCharset(bad));
    }
}
