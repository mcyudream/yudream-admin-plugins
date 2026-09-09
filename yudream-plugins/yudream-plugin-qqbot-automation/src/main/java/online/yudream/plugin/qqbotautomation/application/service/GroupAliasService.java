package online.yudream.plugin.qqbotautomation.application.service;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingGroup;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Official QQ often returns group openids without a human-readable name.
 * Administrators can store a local alias and send a one-shot identify message into the group.
 */
public class GroupAliasService {
    static final String COLLECTION = "group-alias";
    private static final int PAGE_SIZE = 200;
    private static final int IDENTIFY_TIMEOUT_SECONDS = 20;
    private final PluginDocumentStore documents;
    private final FrameworkServices framework;

    public GroupAliasService(PluginDocumentStore documents, FrameworkServices framework) {
        this.documents = documents;
        this.framework = framework;
    }

    public List<Map<String, Object>> list(String connectionId) {
        requireId(connectionId, "connectionId");
        Map<String, String> aliases = aliases(connectionId);
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PluginMessagingGroup group : framework.messaging().groups(connectionId)) {
            if (group == null || blank(group.id()) || !seen.add(group.id())) {
                continue;
            }
            rows.add(row(group.id(), group.name(), aliases.get(group.id())));
        }
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (seen.add(alias.getKey())) {
                rows.add(row(alias.getKey(), alias.getKey(), alias.getValue()));
            }
        }
        return List.copyOf(rows);
    }

    public Map<String, Object> save(String connectionId, String channelId, String alias) {
        requireId(connectionId, "connectionId");
        requireId(channelId, "channelId");
        String normalized = alias == null ? "" : alias.trim();
        if (normalized.length() > 40) {
            throw new IllegalArgumentException("群备注最多 40 个字");
        }
        String id = id(connectionId, channelId);
        if (normalized.isEmpty()) {
            documents.delete(COLLECTION, id);
            return row(channelId, sourceName(connectionId, channelId), "");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("connectionId", connectionId);
        document.put("channelId", channelId);
        document.put("alias", normalized);
        documents.save(COLLECTION, id, document);
        return row(channelId, sourceName(connectionId, channelId), normalized);
    }

    public Map<String, Object> identify(String connectionId, String channelId) {
        requireId(connectionId, "connectionId");
        requireId(channelId, "channelId");
        String alias = aliases(connectionId).getOrDefault(channelId, "");
        StringBuilder text = new StringBuilder("【群识别】这条消息用来确认当前是哪个群。\nopenid：").append(channelId);
        if (!blank(alias)) {
            text.append("\n后台备注：").append(alias);
        }
        try {
            framework.messaging().sendToChannel(connectionId, channelId, new PluginMessageContent(
                    PluginMessageContent.Type.TEXT, text.toString(), null, Map.of("message_scene", "group")))
                    .toCompletableFuture()
                    .get(IDENTIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("识别消息发送超时，请确认机器人仍在该群且近期有过互动", exception);
        } catch (Exception exception) {
            Throwable cause = exception instanceof CompletionException && exception.getCause() != null
                    ? exception.getCause() : exception;
            String message = cause.getMessage();
            throw new IllegalStateException(blank(message) ? "识别消息发送失败" : message, cause);
        }
        return Map.of("sent", true, "channelId", channelId);
    }

    private Map<String, String> aliases(String connectionId) {
        Map<String, String> aliases = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findByField(COLLECTION, "connectionId", connectionId, page, PAGE_SIZE);
            for (Map<String, Object> document : batch) {
                String channelId = text(document.get("channelId"));
                String alias = text(document.get("alias"));
                if (!blank(channelId) && !blank(alias)) {
                    aliases.put(channelId, alias);
                }
            }
            if (batch.size() < PAGE_SIZE) {
                return aliases;
            }
            page += 1;
        }
    }

    private String sourceName(String connectionId, String channelId) {
        return framework.messaging().groups(connectionId).stream()
                .filter(group -> channelId.equals(group.id()))
                .map(PluginMessagingGroup::name)
                .filter(name -> !blank(name) && !channelId.equals(name))
                .findFirst()
                .orElse("");
    }

    private static Map<String, Object> row(String channelId, String sourceName, String alias) {
        String named = blank(sourceName) || channelId.equals(sourceName) ? "" : sourceName;
        String note = alias == null ? "" : alias;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", channelId);
        row.put("name", firstNonBlank(note, named, channelId));
        row.put("alias", note);
        row.put("sourceName", named);
        return row;
    }

    private static String id(String connectionId, String channelId) {
        return connectionId + ":" + channelId;
    }

    private static void requireId(String value, String field) {
        if (blank(value)) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return "";
    }
}
