package online.yudream.plugin.aichatbot.bootstrap;

import online.yudream.base.plugin.spi.system.messaging.PluginEvent;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @ 出站由代码注入，不依赖模型拼协议标签。
 * 用户明确 @ 机器人时，在正文前插入当前发言者 mention；随机插话不加。
 */
final class AiChatbotMentionOutbound {
    private static final Pattern PROTOCOL_MENTION = Pattern.compile(
            "(?i)<qqbot-at-user\\s+id=\"[^\"]*\"\\s*/>|\\[CQ:at,[^\\]]*]|<@!?[^>]+>");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private AiChatbotMentionOutbound() {
    }

    static String decorate(PluginEvent event, String text) {
        String body = strip(text);
        if (event == null || !shouldMention(event)) {
            return body;
        }
        String mention = format(event);
        if (mention.isBlank()) {
            return body;
        }
        if (body.isBlank()) {
            return mention;
        }
        return mention + " " + body;
    }

    static boolean shouldMention(PluginEvent event) {
        if (!directedAtBot(event) || privateScene(event)) {
            return false;
        }
        return true;
    }

    static boolean directedAtBot(PluginEvent event) {
        if (event == null) {
            return false;
        }
        String selfId = text(event.selfId());
        if (!selfId.isBlank() && mentions(event).contains(selfId)) {
            return true;
        }
        return officialDirectedAtBot(event);
    }

    static Map<String, Object> referrer(PluginEvent event) {
        return event == null || event.referrer() == null ? Map.of() : event.referrer();
    }

    static String format(PluginEvent event) {
        String userId = text(event == null ? null : event.userId());
        if (!SAFE_ID.matcher(userId).matches() || userId.equals(text(event.selfId()))) {
            return "";
        }
        if (officialDirectedAtBot(event)) {
            return "<qqbot-at-user id=\"" + userId + "\" />";
        }
        return "[CQ:at,qq=" + userId + "]";
    }

    static String strip(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return PROTOCOL_MENTION.matcher(text).replaceAll(" ").replaceAll(" {2,}", " ").strip();
    }

    static boolean officialDirectedAtBot(PluginEvent event) {
        if (event == null) {
            return false;
        }
        Object referrerFlag = referrer(event).get("mentionSelf");
        if (truthy(referrerFlag)) {
            return true;
        }
        Object nativeData = event.nativeData();
        if (!(nativeData instanceof Map<?, ?> data)) {
            return false;
        }
        if (truthy(data.get("mention_self"))) {
            return true;
        }
        Object nativeType = data.get("native_type");
        if (nativeType == null) {
            return false;
        }
        String type = String.valueOf(nativeType);
        return "GROUP_AT_MESSAGE_CREATE".equals(type)
                || "AT_MESSAGE_CREATE".equals(type)
                || "C2C_MESSAGE_CREATE".equals(type)
                || "DIRECT_MESSAGE_CREATE".equals(type)
                || "INTERACTION_CREATE".equals(type);
    }

    private static boolean privateScene(PluginEvent event) {
        Object scene = referrer(event).get("message_scene");
        if (scene == null && event.nativeData() instanceof Map<?, ?> data) {
            scene = data.get("message_scene");
        }
        String value = text(scene == null ? null : String.valueOf(scene)).toLowerCase();
        return "friend".equals(value) || "private".equals(value) || "dm".equals(value);
    }

    static List<String> mentions(PluginEvent event) {
        if (event == null) {
            return List.of();
        }
        Object value = referrer(event).get("mentions");
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
