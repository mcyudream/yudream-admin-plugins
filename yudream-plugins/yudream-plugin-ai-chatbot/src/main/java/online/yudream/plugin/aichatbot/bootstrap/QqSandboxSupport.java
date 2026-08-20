package online.yudream.plugin.aichatbot.bootstrap;

import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingRawService;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class QqSandboxSupport {
    private static final String DIAGNOSTIC_ACTION = "devtools_sandbox_diagnostic";

    private final String connectionId;
    private final String policyConnectionId;
    private final String sessionId;
    private final RandomMode randomMode;

    private QqSandboxSupport(String connectionId, String policyConnectionId, String sessionId, RandomMode randomMode) {
        this.connectionId = connectionId;
        this.policyConnectionId = policyConnectionId;
        this.sessionId = sessionId;
        this.randomMode = randomMode;
    }

    static QqSandboxSupport from(PluginEvent event) {
        String connectionId = text(event.connectionId());
        String sessionId = text(event.referrer().get("sandboxSessionId"));
        if (connectionId.isBlank() || sessionId.isBlank()) {
            return new QqSandboxSupport(connectionId, connectionId, "", RandomMode.REAL);
        }
        String policyConnectionId = text(event.referrer().get("sandboxPolicyConnectionId"));
        return new QqSandboxSupport(connectionId, policyConnectionId.isBlank() ? connectionId : policyConnectionId, sessionId,
                RandomMode.from(event.referrer().get("sandboxRandomMode")));
    }

    boolean active() {
        return connectionId != null && !connectionId.isBlank() && !sessionId.isBlank();
    }

    boolean persistentWritesAllowed() {
        return !active();
    }

    String messagingConnectionId() {
        return connectionId;
    }

    String policyConnectionId() {
        return policyConnectionId;
    }

    String policyGroupId(String channelId) {
        return policyConnectionId + ":" + channelId;
    }

    String sessionId() {
        return sessionId;
    }

    boolean randomHit(BooleanSupplier realSample) {
        return switch (randomMode) {
            case REAL -> realSample.getAsBoolean();
            case FORCE_HIT -> true;
            case FORCE_MISS -> false;
        };
    }

    static CompletionStage<Void> afterReply(CompletionStage<Void> replyCapture,
                                            Supplier<CompletionStage<Void>> terminalLifecycle) {
        return replyCapture.handle((ignored, error) -> null)
                .thenCompose(ignored -> terminalLifecycle.get().handle((value, error) -> null));
    }

    TriggerDecision trigger(boolean mentioned, BooleanSupplier realSample, BooleanSupplier policyGate) {
        boolean randomHit = !mentioned && randomHit(realSample);
        if (!mentioned && !randomHit) {
            return new TriggerDecision(false, "", "RANDOM_MISS");
        }
        if (!policyGate.getAsBoolean()) {
            return new TriggerDecision(false, mentioned ? "MENTION" : "RANDOM", "POLICY_BLOCKED");
        }
        return new TriggerDecision(true, mentioned ? "MENTION" : "RANDOM", "");
    }

    CompletionStage<Void> diagnostic(PluginMessagingRawService raw, String milestone, Map<String, Object> details) {
        if (!active()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sandboxSessionId", sessionId);
        payload.put("pluginCode", AiChatbotPlugin.CODE);
        payload.put("milestone", milestone);
        if (details != null) {
            details.forEach((key, value) -> {
                if (key != null && value != null) {
                    payload.put(key, value);
                }
            });
        }
        try {
            return raw.invoke(connectionId, DIAGNOSTIC_ACTION, Map.copyOf(payload))
                    .handle((ignored, error) -> null);
        } catch (RuntimeException ignored) {
            // Sandbox diagnostics are observational and must never affect chatbot behavior.
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    record TriggerDecision(boolean triggered, String mode, String blockReason) { }

    private enum RandomMode {
        REAL,
        FORCE_HIT,
        FORCE_MISS;

        private static RandomMode from(Object value) {
            try {
                return value == null ? REAL : valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return REAL;
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
