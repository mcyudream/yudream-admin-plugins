package online.yudream.plugin.webcard.application;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WebCardScheduler implements AutoCloseable {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("web-card-scheduler").factory());
    private final String nodeId = UUID.randomUUID().toString();
    private final WebCardApplicationService service;
    public WebCardScheduler(WebCardApplicationService service) { this.service = service; }
    public void start() { executor.scheduleWithFixedDelay(() -> { try { service.runDueJobs(nodeId); } catch (Exception ignored) { } }, 10, 30, TimeUnit.SECONDS); }
    @Override public void close() { executor.shutdownNow(); }
}
