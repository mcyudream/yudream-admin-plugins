package online.yudream.base.plugin.mcwiki.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.mcwiki.infrastructure.FakeDocumentStore;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobServiceTest {
    @SuppressWarnings("unchecked")
    private static final class RecordingSubscriber implements PluginSseStream.Subscriber {
        final List<String> types = new ArrayList<>(); final List<Map<String, Object>> payloads = new ArrayList<>(); boolean completed;
        public void send(String event, Object value) { types.add(event); payloads.add((Map<String, Object>) value); }
        public void complete() { completed = true; }
        public void error(Throwable error) { fail(error); }
    }

    private static JobService.Work sampleWork() {
        return (version, control) -> { control.progress("DOWNLOAD", 0, 2, "开始下载"); control.log("INFO", "下载完成"); control.progress("PARSE", 1, 2, "解析中"); };
    }

    @Test void persistsEventsAndCompletesJob() {
        FakeDocumentStore store = new FakeDocumentStore();
        JobService service = new JobService(store, Runnable::run, sampleWork());
        JobService.Job job = service.create("1.20.6");
        assertEquals("DONE", service.get(job.jobId()).status());
        assertEquals(5, store.count("import_job_events"));
    }

    @Test void replaysPersistedEventsAfterReload() {
        FakeDocumentStore store = new FakeDocumentStore();
        JobService first = new JobService(store, Runnable::run, sampleWork());
        JobService.Job job = first.create("1.20.6");
        JobService reloaded = new JobService(store, Runnable::run, sampleWork());
        PluginSseStream stream = reloaded.stream(job.streamId());
        assertNotNull(stream);
        RecordingSubscriber subscriber = new RecordingSubscriber();
        stream.subscribe(subscriber);
        assertEquals(5, subscriber.types.size());
        assertEquals("progress", subscriber.types.get(0));
        assertEquals("state", subscriber.types.get(4));
        assertEquals("DONE", subscriber.payloads.get(4).get("status"));
        assertEquals("导入完成", subscriber.payloads.get(4).get("message"));
        assertTrue(subscriber.completed);
        assertEquals(5, reloaded.eventsAfter(job.streamId(), 0).size());
        assertEquals(4, reloaded.eventsAfter(job.streamId(), 1).size());
    }

    @Test void completedJobStreamsFromPersistedEventsOnSameInstance() {
        FakeDocumentStore store = new FakeDocumentStore();
        JobService service = new JobService(store, Runnable::run, sampleWork());
        JobService.Job job = service.create("1.20.6");
        PluginSseStream stream = service.stream(job.streamId());
        assertNotNull(stream);
        RecordingSubscriber subscriber = new RecordingSubscriber();
        stream.subscribe(subscriber);
        assertEquals(5, subscriber.types.size());
        assertTrue(subscriber.completed);
    }

    @Test void pendingJobStreamsLiveBus() {
        FakeDocumentStore store = new FakeDocumentStore();
        List<Runnable> queue = new ArrayList<>();
        JobService service = new JobService(store, queue::add, sampleWork());
        JobService.Job job = service.create("1.20.6");
        assertTrue(service.stream(job.streamId()) instanceof JobLogBus);
    }

    @Test void unknownStreamReturnsNull() {
        FakeDocumentStore store = new FakeDocumentStore();
        JobService service = new JobService(store, Runnable::run, sampleWork());
        assertNull(service.stream("missing"));
        assertEquals(List.of(), service.eventsAfter("missing", 0));
    }

    @Test void deleteRemovesJobAndItsEvents() {
        FakeDocumentStore store = new FakeDocumentStore();
        JobService service = new JobService(store, Runnable::run, sampleWork());
        JobService.Job job = service.create("1.20.6");
        service.delete(job.jobId());
        assertEquals(0, store.count("import_jobs"));
        assertEquals(0, store.count("import_job_events"));
        assertNull(service.stream(job.streamId()));
    }
}
