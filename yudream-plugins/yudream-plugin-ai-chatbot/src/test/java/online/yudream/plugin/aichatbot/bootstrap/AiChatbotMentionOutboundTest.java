package online.yudream.plugin.aichatbot.bootstrap;

import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatbotMentionOutboundTest {

    @Test
    void officialMentionPrefixesNativeMarkdownTagAndStripsModelJunk() {
        PluginEvent event = event(
                Map.of("mentionSelf", true, "mentions", List.of("bot-open")),
                Map.of("mention_self", true, "native_type", "GROUP_AT_MESSAGE_CREATE"),
                "member-open",
                "bot-open");

        String outbound = AiChatbotMentionOutbound.decorate(event,
                "<qqbot-at-user id=\"guess\" /> <@!bot-open> 你好");

        assertEquals("<qqbot-at-user id=\"member-open\" /> 你好", outbound);
        assertTrue(AiChatbotMentionOutbound.directedAtBot(event));
        assertTrue(AiChatbotMentionOutbound.officialDirectedAtBot(event));
    }

    @Test
    void milkyMentionPrefixesCqAt() {
        PluginEvent event = event(
                Map.of("mentions", List.of("bot-1", "member-2")),
                Map.of(),
                "user-1",
                "bot-1");

        assertEquals("[CQ:at,qq=user-1] 先绑定账号。",
                AiChatbotMentionOutbound.decorate(event, "先绑定账号。"));
        assertFalse(AiChatbotMentionOutbound.officialDirectedAtBot(event));
    }

    @Test
    void randomReplyDoesNotMention() {
        PluginEvent event = event(Map.of(), Map.of(), "user-1", "bot-1");

        assertEquals("路过打个招呼。", AiChatbotMentionOutbound.decorate(event, "路过打个招呼。"));
        assertFalse(AiChatbotMentionOutbound.directedAtBot(event));
    }

    @Test
    void rejectsUnsafeOrSelfIds() {
        PluginEvent unsafe = event(Map.of("mentionSelf", true), Map.of("mention_self", true),
                "id\" onerror=1", "bot-1");
        PluginEvent self = event(Map.of("mentions", List.of("bot-1")), Map.of(), "bot-1", "bot-1");

        assertEquals("你好", AiChatbotMentionOutbound.decorate(unsafe, "你好"));
        assertEquals("你好", AiChatbotMentionOutbound.decorate(self, "你好"));
    }

    @Test
    void privateChatDoesNotMention() {
        PluginEvent event = event(
                Map.of("mentions", List.of("bot-1"), "message_scene", "friend"),
                Map.of("message_scene", "friend", "native_type", "C2C_MESSAGE_CREATE", "mention_self", true),
                "user-1",
                "bot-1");

        assertEquals("你好", AiChatbotMentionOutbound.decorate(event, "你好"));
        assertTrue(AiChatbotMentionOutbound.directedAtBot(event));
        assertFalse(AiChatbotMentionOutbound.shouldMention(event));
    }

    @Test
    void blankBodyKeepsMentionOnly() {
        PluginEvent event = event(Map.of("mentions", List.of("bot-1")), Map.of(), "user-1", "bot-1");

        assertEquals("[CQ:at,qq=user-1]", AiChatbotMentionOutbound.decorate(event, "   "));
        assertEquals("", AiChatbotMentionOutbound.strip(" <qqbot-at-user id=\"x\" /> "));
    }

    private PluginEvent event(Map<String, Object> referrer, Map<String, Object> nativeData,
                              String userId, String selfId) {
        return new PluginEvent("sequence-1", "message_receive", "milky", userId, "group-1",
                "hello", null, null, referrer, "message_receive", nativeData, "connection-1", selfId, "message-1");
    }
}
