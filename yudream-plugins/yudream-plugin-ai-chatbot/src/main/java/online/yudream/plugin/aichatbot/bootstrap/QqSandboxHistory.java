package online.yudream.plugin.aichatbot.bootstrap;

import online.yudream.base.plugin.spi.system.ai.PluginAiChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class QqSandboxHistory {
    private static final int MAX_MESSAGES = 32;
    private static final int MAX_SESSIONS = 100;
    private final Map<String, SessionHistory> sessions = new ConcurrentHashMap<>();

    void append(String sessionId, String scope, String role, String content) {
        session(sessionId).append(scope, new PluginAiChatMessage(role, content));
    }

    List<PluginAiChatMessage> read(String sessionId, String scope, int limit,
                                   Supplier<List<PluginAiChatMessage>> productionSeed) {
        return session(sessionId).read(scope, limit, productionSeed);
    }

    void clear() {
        sessions.clear();
    }

    private SessionHistory session(String sessionId) {
        if (sessions.size() >= MAX_SESSIONS && !sessions.containsKey(sessionId)) {
            sessions.keySet().stream().findFirst().ifPresent(sessions::remove);
        }
        return sessions.computeIfAbsent(sessionId, ignored -> new SessionHistory());
    }

    private static final class SessionHistory {
        private final Map<String, ScopeHistory> scopes = new ConcurrentHashMap<>();

        private void append(String scope, PluginAiChatMessage message) {
            scopes.computeIfAbsent(scope, ignored -> new ScopeHistory()).append(message);
        }

        private List<PluginAiChatMessage> read(String scope, int limit,
                                               Supplier<List<PluginAiChatMessage>> productionSeed) {
            return scopes.computeIfAbsent(scope, ignored -> new ScopeHistory()).read(limit, productionSeed);
        }
    }

    private static final class ScopeHistory {
        private List<PluginAiChatMessage> messages = new ArrayList<>();
        private boolean seeded;

        private synchronized void append(PluginAiChatMessage message) {
            messages.add(message);
            messages = tail(messages, MAX_MESSAGES);
        }

        private synchronized List<PluginAiChatMessage> read(int limit,
                                                            Supplier<List<PluginAiChatMessage>> productionSeed) {
            if (!seeded) {
                List<PluginAiChatMessage> combined = new ArrayList<>(productionSeed.get());
                combined.addAll(messages);
                messages = tail(combined, MAX_MESSAGES);
                seeded = true;
            }
            return List.copyOf(tail(messages, limit));
        }

        private static List<PluginAiChatMessage> tail(List<PluginAiChatMessage> values, int limit) {
            int safeLimit = Math.max(0, limit);
            int from = Math.max(0, values.size() - safeLimit);
            return new ArrayList<>(values.subList(from, values.size()));
        }
    }
}
