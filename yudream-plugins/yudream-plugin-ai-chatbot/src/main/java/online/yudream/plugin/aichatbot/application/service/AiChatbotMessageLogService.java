package online.yudream.plugin.aichatbot.application.service;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 近一天群消息库：记录群内每条消息（无论是否触发回复），超过 24 小时的自动清理；
 * 画像分析时按用户从中随机抽取配置数条作为辅助证据。
 */
public class AiChatbotMessageLogService {
    private static final Logger LOGGER = Logger.getLogger(AiChatbotMessageLogService.class.getName());
    public static final String COLLECTION = "ai_chatbot_message_log";
    private static final long RETENTION_MS = 24L * 3600_000L;
    private static final long PURGE_INTERVAL_MS = 30L * 60_000L;
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int SCAN_PAGE = 200;
    private static final int SCAN_MAX_PAGES = 10;

    private final PluginDocumentStore documents;
    private final Map<String, Long> lastPurge = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public AiChatbotMessageLogService(PluginDocumentStore documents) {
        this.documents = Objects.requireNonNull(documents, "documents");
    }

    public record LoggedMessage(String userId, String nickname, String content, long occurredAt) { }

    /** 记录一条群消息并按需清理过期数据（每群 30 分钟最多全量清理一次）。 */
    public void log(String connectionId, String channelId, String userId, String nickname, String content) {
        if (connectionId == null || channelId == null || userId == null || content == null || content.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        String text = content.trim();
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("id", UUID.randomUUID().toString());
        document.put("connectionId", connectionId);
        document.put("channelId", channelId);
        document.put("groupKey", groupKey(connectionId, channelId));
        document.put("userKey", userKey(connectionId, channelId, userId));
        document.put("userId", userId);
        document.put("nickname", nickname == null ? "" : nickname);
        document.put("content", text.substring(0, Math.min(text.length(), MAX_CONTENT_LENGTH)));
        document.put("occurredAt", now);
        try {
            documents.save(COLLECTION, String.valueOf(document.get("id")), document);
            purgeExpired(connectionId, channelId, now);
        }
        catch (Exception error) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [AI Chatbot] message log write failed: " + error.getMessage(), error);
        }
    }

    /** 随机抽取该用户近 24 小时内的消息，最多 limit 条。 */
    public List<LoggedMessage> sample(String connectionId, String channelId, String userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<LoggedMessage> recent = recentByUser(connectionId, channelId, userId);
        if (recent.size() <= limit) {
            return recent;
        }
        List<LoggedMessage> shuffled = new ArrayList<>(recent);
        Collections.shuffle(shuffled, random);
        return List.copyOf(shuffled.subList(0, limit));
    }

    /** 该用户近 24 小时内已记录的消息条数，用于“条数小于配置数则不分析”的判定。 */
    public long countRecent(String connectionId, String channelId, String userId) {
        return recentByUser(connectionId, channelId, userId).size();
    }

    private List<LoggedMessage> recentByUser(String connectionId, String channelId, String userId) {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        List<LoggedMessage> result = new ArrayList<>();
        for (Map<String, Object> row : scan("userKey", userKey(connectionId, channelId, userId))) {
            long occurredAt = longValue(row.get("occurredAt"));
            if (occurredAt < cutoff) {
                continue;
            }
            String content = row.get("content") == null ? "" : String.valueOf(row.get("content"));
            if (content.isBlank()) {
                continue;
            }
            result.add(new LoggedMessage(String.valueOf(row.get("userId")),
                    row.get("nickname") == null ? "" : String.valueOf(row.get("nickname")), content, occurredAt));
        }
        return result;
    }

    private void purgeExpired(String connectionId, String channelId, long now) {
        String key = groupKey(connectionId, channelId);
        Long last = lastPurge.get(key);
        if (last != null && now - last < PURGE_INTERVAL_MS) {
            return;
        }
        lastPurge.put(key, now);
        long cutoff = now - RETENTION_MS;
        try {
            for (Map<String, Object> row : scan("groupKey", key)) {
                if (longValue(row.get("occurredAt")) < cutoff && row.get("id") != null) {
                    documents.delete(COLLECTION, String.valueOf(row.get("id")));
                }
            }
        }
        catch (Exception error) {
            LOGGER.log(Level.WARNING, "[YuDreamAdmin] [AI Chatbot] message log purge failed: " + error.getMessage(), error);
        }
    }

    private List<Map<String, Object>> scan(String field, String value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int page = 1; page <= SCAN_MAX_PAGES; page++) {
            List<Map<String, Object>> rows = documents.findByField(COLLECTION, field, value, page, SCAN_PAGE);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            result.addAll(rows);
            if (rows.size() < SCAN_PAGE) {
                break;
            }
        }
        return result;
    }

    private static String groupKey(String connectionId, String channelId) {
        return connectionId + ":" + channelId;
    }

    private static String userKey(String connectionId, String channelId, String userId) {
        return connectionId + ":" + channelId + ":" + userId;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        }
        catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
