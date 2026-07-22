package online.yudream.plugin.webcard.application;

import online.yudream.base.plugin.spi.http.PluginSseStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentMessageStreamTest {
    @Test
    void startsOnFirstSubscriberAndReplaysEventsToLateSubscriber() throws Exception {
        AgentMessageStream stream = new AgentMessageStream("stream-1", "session-1", value -> {
            value.start();
            value.delta("hello");
            value.completeMessage("hello");
            value.warning("bad plan");
            value.complete();
        });
        RecordingSubscriber first = new RecordingSubscriber();
        stream.subscribe(first);
        assertTrue(first.completed.await(2, TimeUnit.SECONDS));

        RecordingSubscriber late = new RecordingSubscriber();
        stream.subscribe(late);
        assertTrue(late.completed.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("message.start", "message.delta", "message.complete", "proposal.warning"), late.names);
        assertEquals("hello", ((Map<?, ?>) late.payloads.get(1)).get("delta"));
    }

    private static final class RecordingSubscriber implements PluginSseStream.Subscriber {
        private final List<String> names = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);
        @Override public void send(String event, Object payload) { names.add(event); payloads.add(payload); }
        @Override public void complete() { completed.countDown(); }
        @Override public void error(Throwable error) { fail(error); }
    }
}
