package online.yudream.base.plugin.mcnews.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import org.junit.jupiter.api.Test;

class McNewsSettingsTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McNewsSettings settings = new McNewsSettings(new McNewsStore(documents));

    @Test
    void defaultsApplyWhenUnset() {
        assertTrue(settings.enabled());
        assertEquals(30, settings.pollIntervalMinutes());
        assertEquals(50, settings.cacheSize());
        assertFalse(settings.pushOnFirstPoll());
        assertTrue(settings.aiEnabled());
        assertEquals(10, settings.aiMaxItems());
        assertEquals(McNewsSettings.DEFAULT_MESSAGE_TEMPLATE, settings.messageTemplate());
        assertEquals(McNewsSettings.DEFAULT_AI_SYSTEM_PROMPT, settings.aiSystemPrompt());
    }

    @Test
    void updateClampsAndClearsOptionalFields() {
        settings.update(false, 1, 9999, true, false, "prov", "model", 999, 100, "", "");
        assertFalse(settings.enabled());
        assertEquals(McNewsSettings.MIN_POLL_INTERVAL, settings.pollIntervalMinutes());
        assertEquals(McNewsSettings.MAX_CACHE_SIZE, settings.cacheSize());
        assertTrue(settings.pushOnFirstPoll());
        assertFalse(settings.aiEnabled());
        assertEquals("prov", settings.aiProviderCode());
        assertEquals("model", settings.aiModelCode());
        assertEquals(McNewsSettings.MAX_AI_ITEMS, settings.aiMaxItems());
        assertEquals(McNewsSettings.MIN_CONTENT_CHARS, settings.aiContentMaxChars());
        // 空字符串恢复默认
        assertEquals(McNewsSettings.DEFAULT_MESSAGE_TEMPLATE, settings.messageTemplate());
        assertEquals(McNewsSettings.DEFAULT_AI_SYSTEM_PROMPT, settings.aiSystemPrompt());

        // null 字段保持不变
        settings.update(true, null, null, null, null, null, null, null, null, null, null);
        assertTrue(settings.enabled());
        assertFalse(settings.aiEnabled());
    }

    @Test
    void recordPollWritesStatusWithoutBreakingSettings() {
        settings.update(null, 15, null, null, null, null, null, null, null, null, null);
        settings.recordPoll(42L, "ok");
        assertEquals(42L, settings.lastPollAt());
        assertEquals("ok", settings.lastPollSummary());
        assertEquals(15, settings.pollIntervalMinutes());
    }

    @Test
    void stripNullsRemovesNullRecursively() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("k", null);
        nested.put("j", "v");
        Map<String, Object> input = new HashMap<>();
        input.put("a", null);
        input.put("b", "x");
        input.put("list", java.util.Arrays.asList("p", null, nested));
        Map<String, Object> result = McNewsStore.stripNulls(input);
        assertEquals("x", result.get("b"));
        assertFalse(result.containsKey("a"));
        assertEquals(List.of("p", Map.of("j", "v")), result.get("list"));
    }

}
