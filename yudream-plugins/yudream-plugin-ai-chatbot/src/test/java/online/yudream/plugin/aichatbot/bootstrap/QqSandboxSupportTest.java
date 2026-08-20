package online.yudream.plugin.aichatbot.bootstrap;

import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingRawService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqSandboxSupportTest {

    @Test
    void realModeKeepsProductionSamplingOutcome() {
        AtomicInteger samples = new AtomicInteger();
        QqSandboxSupport sandbox = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-1",
                "sandboxRandomMode", "REAL"
        )));

        boolean result = sandbox.randomHit(() -> {
            samples.incrementAndGet();
            return true;
        });

        assertTrue(result);
        assertEquals(1, samples.get());
    }

    @Test
    void forceModesReplaceOnlyRandomSamplingOutcome() {
        AtomicInteger samples = new AtomicInteger();
        QqSandboxSupport forceHit = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-1",
                "sandboxRandomMode", "FORCE_HIT"
        )));
        QqSandboxSupport forceMiss = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-2",
                "sandboxRandomMode", "FORCE_MISS"
        )));

        assertTrue(forceHit.randomHit(() -> {
            samples.incrementAndGet();
            return false;
        }));
        assertFalse(forceMiss.randomHit(() -> {
            samples.incrementAndGet();
            return true;
        }));
        assertEquals(0, samples.get());
    }

    @Test
    void ignoresReservedModeWithoutSyntheticSession() {
        AtomicInteger samples = new AtomicInteger();
        QqSandboxSupport sandbox = QqSandboxSupport.from(event(Map.of(
                "sandboxRandomMode", "FORCE_HIT"
        )));

        boolean result = sandbox.randomHit(() -> {
            samples.incrementAndGet();
            return false;
        });

        assertFalse(sandbox.active());
        assertFalse(result);
        assertEquals(1, samples.get());
    }

    @Test
    void forcedHitStillUsesRealPolicyGate() {
        AtomicInteger samples = new AtomicInteger();
        AtomicInteger policyChecks = new AtomicInteger();
        QqSandboxSupport sandbox = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-1",
                "sandboxRandomMode", "FORCE_HIT"
        )));

        QqSandboxSupport.TriggerDecision decision = sandbox.trigger(false, () -> {
            samples.incrementAndGet();
            return false;
        }, () -> {
            policyChecks.incrementAndGet();
            return false;
        });

        assertFalse(decision.triggered());
        assertEquals("RANDOM", decision.mode());
        assertEquals("POLICY_BLOCKED", decision.blockReason());
        assertEquals(0, samples.get());
        assertEquals(1, policyChecks.get());
    }

    @Test
    void forcedMissDoesNotEvaluatePolicyGate() {
        AtomicInteger policyChecks = new AtomicInteger();
        QqSandboxSupport sandbox = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-1",
                "sandboxRandomMode", "FORCE_MISS"
        )));

        QqSandboxSupport.TriggerDecision decision = sandbox.trigger(false, () -> true, () -> {
            policyChecks.incrementAndGet();
            return true;
        });

        assertFalse(decision.triggered());
        assertEquals("RANDOM_MISS", decision.blockReason());
        assertEquals(0, policyChecks.get());
    }

    @Test
    void selectsReservedPolicyConnectionOnlyForActiveSandbox() {
        QqSandboxSupport sandbox = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-1",
                "sandboxPolicyConnectionId", "production-connection"
        )));
        QqSandboxSupport production = QqSandboxSupport.from(event(Map.of(
                "sandboxPolicyConnectionId", "production-connection"
        )));

        assertEquals("production-connection", sandbox.policyConnectionId());
        assertEquals("connection-1", sandbox.messagingConnectionId());
        assertFalse(sandbox.persistentWritesAllowed());
        assertEquals("connection-1", production.policyConnectionId());
        assertTrue(production.persistentWritesAllowed());
    }

    @Test
    void blankPolicyConnectionFallsBackToSyntheticConnection() {
        QqSandboxSupport sandbox = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-1",
                "sandboxPolicyConnectionId", "  "
        )));

        assertEquals("connection-1", sandbox.policyConnectionId());
    }

    @Test
    void usesPolicyConnectionForSemanticMemoryGroupIdentity() {
        QqSandboxSupport sandbox = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-1",
                "sandboxPolicyConnectionId", "production-connection"
        )));

        assertEquals("production-connection:group-1", sandbox.policyGroupId("group-1"));
        assertEquals("connection-1", sandbox.messagingConnectionId());
    }

    @Test
    void terminalLifecycleWaitsForReplyCapture() {
        List<String> milestones = new ArrayList<>();
        CompletableFuture<Void> replyCapture = new CompletableFuture<>();

        CompletionStage<Void> lifecycle = QqSandboxSupport.afterReply(replyCapture, () -> {
            milestones.add("agent_complete");
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(milestones.isEmpty());
        assertFalse(lifecycle.toCompletableFuture().isDone());

        replyCapture.complete(null);

        lifecycle.toCompletableFuture().join();
        assertEquals(List.of("agent_complete"), milestones);
    }

    @Test
    void lifecycleAlsoWaitsForTerminalDiagnosticDelivery() {
        CompletableFuture<Void> replyCapture = CompletableFuture.completedFuture(null);
        CompletableFuture<Void> terminalDiagnostic = new CompletableFuture<>();

        CompletionStage<Void> lifecycle = QqSandboxSupport.afterReply(replyCapture, () -> terminalDiagnostic);

        assertFalse(lifecycle.toCompletableFuture().isDone());
        terminalDiagnostic.complete(null);
        lifecycle.toCompletableFuture().join();
        assertTrue(lifecycle.toCompletableFuture().isDone());
    }

    @Test
    void unknownModeFallsBackToRealSampling() {
        AtomicInteger samples = new AtomicInteger();
        QqSandboxSupport sandbox = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-1",
                "sandboxRandomMode", "unsupported"
        )));

        assertTrue(sandbox.randomHit(() -> {
            samples.incrementAndGet();
            return true;
        }));
        assertEquals(1, samples.get());
    }

    @Test
    void emitsDiagnosticsOnlyForSyntheticSession() {
        RecordingRawService raw = new RecordingRawService();
        QqSandboxSupport sandbox = QqSandboxSupport.from(event(Map.of(
                "sandboxSessionId", "session-1",
                "sandboxRandomMode", "FORCE_HIT"
        )));

        sandbox.diagnostic(raw, "trigger_result", Map.of("triggered", true));
        QqSandboxSupport.from(event(Map.of())).diagnostic(raw, "trigger_result", Map.of("triggered", false));

        assertEquals(1, raw.calls.size());
        RawCall call = raw.calls.getFirst();
        assertEquals("connection-1", call.connectionId());
        assertEquals("devtools_sandbox_diagnostic", call.action());
        assertEquals("session-1", call.payload().get("sandboxSessionId"));
        assertEquals("trigger_result", call.payload().get("milestone"));
        assertEquals("ai-chatbot", call.payload().get("pluginCode"));
        assertEquals(true, call.payload().get("triggered"));
    }

    private PluginEvent event(Map<String, Object> referrer) {
        return new PluginEvent("sequence-1", "message_receive", "milky", "user-1", "group-1",
                "hello", null, null, referrer, "message_receive", Map.of(), "connection-1", "bot-1", "message-1");
    }

    private record RawCall(String connectionId, String action, Map<String, Object> payload) { }

    private static final class RecordingRawService implements PluginMessagingRawService {
        private final List<RawCall> calls = new ArrayList<>();

        @Override
        public CompletableFuture<Map<String, Object>> invoke(String connectionId, String method, Map<String, Object> payload) {
            calls.add(new RawCall(connectionId, method, payload));
            return CompletableFuture.completedFuture(Map.of());
        }
    }
}
