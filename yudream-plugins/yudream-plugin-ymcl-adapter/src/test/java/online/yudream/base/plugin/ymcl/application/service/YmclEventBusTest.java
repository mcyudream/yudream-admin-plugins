package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.ymcl.interfaces.controller.YmclEventsController;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YmclEventBusTest {

    @Test
    void replayFiltersByLastEventId() {
        YmclEventBus bus = new YmclEventBus();
        bus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "a"));
        bus.publish(YmclEventBus.TYPE_PACK_PUBLISHED, Map.of("packId", "p1"));

        List<String> types = new ArrayList<>();
        bus.open(1L).subscribe(recordingSubscriber(types, null));
        assertEquals(List.of(YmclEventBus.TYPE_PACK_PUBLISHED, YmclEventBus.TYPE_CONNECTED), types);
    }

    @Test
    void fullReplayWhenNoResumeToken() {
        YmclEventBus bus = new YmclEventBus();
        bus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "a"));
        bus.publish(YmclEventBus.TYPE_PACK_UPDATED, Map.of("packId", "p1"));

        List<String> types = new ArrayList<>();
        bus.open(null).subscribe(recordingSubscriber(types, null));
        assertEquals(
                List.of(YmclEventBus.TYPE_MANIFEST_UPDATED, YmclEventBus.TYPE_PACK_UPDATED, YmclEventBus.TYPE_CONNECTED),
                types);
    }

    @Test
    void envelopeCarriesIdTypeAtPayload() {
        YmclEventBus bus = new YmclEventBus();
        AtomicReference<Object> captured = new AtomicReference<>();
        bus.open(null).subscribe(recordingSubscriber(new ArrayList<>(), captured));
        captured.set(null);
        bus.publish(YmclEventBus.TYPE_BINDING_UPDATED, Map.of("serverId", "s1"));

        assertNotNull(captured.get());
        assertTrue(captured.get() instanceof Map<?, ?> envelope);
        Map<?, ?> envelope = (Map<?, ?>) captured.get();
        assertEquals(YmclEventBus.TYPE_BINDING_UPDATED, envelope.get("type"));
        assertNotNull(envelope.get("id"));
        assertNotNull(envelope.get("at"));
        assertEquals(Map.of("serverId", "s1"), envelope.get("payload"));
    }

    @Test
    void resolveLastEventIdPrefersHeaderThenQuery() {
        PluginHttpRequest fromHeader = new PluginHttpRequest(
                "GET", "/v1/events",
                Map.of("Last-Event-ID", List.of("12")),
                Map.of("lastEventId", List.of("99")),
                null, Map.of(), null);
        assertEquals(12L, YmclEventsController.resolveLastEventId(fromHeader));

        PluginHttpRequest fromQuery = new PluginHttpRequest(
                "GET", "/v1/events",
                Map.of(),
                Map.of("lastEventId", List.of("7")),
                null, Map.of(), null);
        assertEquals(7L, YmclEventsController.resolveLastEventId(fromQuery));

        PluginHttpRequest none = new PluginHttpRequest(
                "GET", "/v1/events", Map.of(), Map.of(), null, Map.of(), null);
        assertNull(YmclEventsController.resolveLastEventId(none));
    }

    @Test
    void heartbeatAndConnectedAreNotRetainedInReplay() {
        YmclEventBus bus = new YmclEventBus();
        bus.startHeartbeat();
        try {
            List<String> types = new ArrayList<>();
            bus.open(null).subscribe(recordingSubscriber(types, null));
            assertEquals(List.of(YmclEventBus.TYPE_CONNECTED), types);

            bus.publish(YmclEventBus.TYPE_HEARTBEAT, Map.of());
            bus.publish(YmclEventBus.TYPE_MANIFEST_UPDATED, Map.of("reason", "x"));

            List<String> late = new ArrayList<>();
            bus.open(null).subscribe(recordingSubscriber(late, null));
            assertEquals(List.of(YmclEventBus.TYPE_MANIFEST_UPDATED, YmclEventBus.TYPE_CONNECTED), late);
            assertFalse(late.contains(YmclEventBus.TYPE_HEARTBEAT));
        } finally {
            bus.stopHeartbeat();
        }
    }

    private static PluginSseStream.Subscriber recordingSubscriber(List<String> types, AtomicReference<Object> captured) {
        return new PluginSseStream.Subscriber() {
            @Override
            public void send(String event, Object data) {
                types.add(event);
                if (captured != null && !YmclEventBus.TYPE_CONNECTED.equals(event)) {
                    captured.set(data);
                }
            }

            @Override
            public void complete() {
            }

            @Override
            public void error(Throwable throwable) {
                throw new AssertionError(throwable);
            }
        };
    }
}
