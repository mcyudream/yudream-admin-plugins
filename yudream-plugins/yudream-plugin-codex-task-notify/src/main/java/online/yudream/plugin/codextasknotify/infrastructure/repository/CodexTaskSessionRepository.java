package online.yudream.plugin.codextasknotify.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.codextasknotify.application.dto.CodexTaskSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CodexTaskSessionRepository {

    private static final String COLLECTION = "task-session";
    private static final int PAGE_SIZE = 200;

    private final PluginDocumentStore documents;

    public CodexTaskSessionRepository(PluginDocumentStore documents) {
        this.documents = documents;
    }

    public CodexTaskSession save(CodexTaskSession session) {
        documents.save(COLLECTION, id(session.userId(), session.taskId()), toDocument(session));
        return session;
    }

    public Optional<CodexTaskSession> find(String userId, String taskId) {
        return documents.findById(COLLECTION, id(userId, taskId)).map(this::fromDocument);
    }

    public List<CodexTaskSession> findActive() {
        List<CodexTaskSession> sessions = new ArrayList<>();
        for (int page = 1; ; page++) {
            List<Map<String, Object>> records = documents.findAll(COLLECTION, page, PAGE_SIZE);
            sessions.addAll(records.stream().map(this::fromDocument)
                    .filter(session -> session.status() == CodexTaskSession.Status.ACTIVE).toList());
            if (records.size() < PAGE_SIZE) {
                return List.copyOf(sessions);
            }
        }
    }

    private Map<String, Object> toDocument(CodexTaskSession session) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("taskId", session.taskId());
        document.put("userId", session.userId());
        document.put("title", session.title());
        document.put("timeoutSeconds", session.timeoutSeconds());
        document.put("lastHeartbeatAt", session.lastHeartbeatAt());
        document.put("status", session.status().name());
        return document;
    }

    private CodexTaskSession fromDocument(Map<String, Object> document) {
        return new CodexTaskSession(
                text(document.get("taskId")),
                text(document.get("userId")),
                text(document.get("title")),
                number(document.get("timeoutSeconds"), 60),
                longNumber(document.get("lastHeartbeatAt"), 0L),
                status(document.get("status"))
        );
    }

    private String id(String userId, String taskId) {
        return userId + ":" + taskId;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private long longNumber(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private CodexTaskSession.Status status(Object value) {
        try {
            return CodexTaskSession.Status.valueOf(text(value));
        } catch (IllegalArgumentException exception) {
            return CodexTaskSession.Status.INTERRUPTED;
        }
    }
}
