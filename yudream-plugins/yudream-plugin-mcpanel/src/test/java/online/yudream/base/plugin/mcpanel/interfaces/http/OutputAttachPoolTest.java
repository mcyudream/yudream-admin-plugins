package online.yudream.base.plugin.mcpanel.interfaces.http;

import online.yudream.base.plugin.mcpanel.acceptance.InMemoryDocumentStore;
import online.yudream.base.plugin.mcpanel.application.service.McpanelInstanceAppService;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelInstance;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.repo.PortAllocationRepository;
import online.yudream.base.plugin.mcpanel.domain.service.PortAllocator;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentMcpanelInstanceRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentNodeRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.repository.DocumentPortAllocationRepository;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出 attach 引用计数池回归：首个订阅 attach、最后一个订阅离开才 detach、
 * 中途离开不互踢（实例级泵为多浏览器共享）。
 */
class OutputAttachPoolTest {

    private final InMemoryDocumentStore documents = new InMemoryDocumentStore();
    private final McpanelNodeRepository nodes = new DocumentNodeRepository(documents, McpanelJson.mapper());
    private final McpanelInstanceRepository instances =
            new DocumentMcpanelInstanceRepository(documents, McpanelJson.mapper());
    private final PortAllocationRepository ports =
            new DocumentPortAllocationRepository(documents, McpanelJson.mapper());
    private final List<String> nodeCalls = new CopyOnWriteArrayList<>();
    private final CountDownLatch attachDone = new CountDownLatch(1);
    private final CountDownLatch detachDone = new CountDownLatch(1);

    private OutputAttachPool pool;

    @BeforeEach
    void setUp() throws Exception {
        documents.clear();
        nodes.save(McpanelNode.create("node-1", "节点", "wss://127.0.0.1:9701", "pinned",
                "a".repeat(64), true, "test", true, 1L)
                .withReported("0.2.0", "h", "a".repeat(64), 1L));
        instances.save(McpanelInstance.create("inst-1", "node-1", "实例", "paper", "1.21.4", "",
                "eclipse-temurin:21-jre", List.of("java", "-jar", "server.jar"), Map.of(),
                1024, 1000, 2048, List.of(), Map.of(), null, "", 1L));
    }

    private McpanelInstanceAppService service() {
        return new McpanelInstanceAppService(instances, nodes, ports,
                (nodeId, method, payload) -> {
                    nodeCalls.add(method);
                    if ("instance.output.subscribe".equals(method)) {
                        attachDone.countDown();
                    }
                    if ("instance.output.unsubscribe".equals(method)) {
                        detachDone.countDown();
                    }
                    return CompletableFuture.completedFuture(Map.of());
                },
                new PortAllocator(ports),
                (actor, action, targetType, targetId, detail, tenantId) -> {
                },
                new McpanelInstanceAppService.TenancyScope() {
                    @Override
                    public boolean canAccess(String scopeKey, McpanelInstance instance) {
                        return true;
                    }

                    @Override
                    public String tenantOf(Object requestContext, McpanelNode node) {
                        return null;
                    }
                },
                new online.yudream.base.plugin.mcpanel.application.service.MinecraftLinkService(java.util.Optional::empty), null);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    private static PluginSseStream.Subscriber noopSubscriber() {
        return new PluginSseStream.Subscriber() {
            @Override
            public void send(String event, Object payload) {
            }

            @Override
            public void complete() {
            }

            @Override
            public void error(Throwable error) {
            }
        };
    }

    @Test
    void refcountAttachesOnceAndDetachesOnlyOnLastLeave() throws Exception {
        McpanelInstanceAppService service = service();
        final PluginSseStream backend = new PluginSseStream() {
            @Override
            public void subscribe(PluginSseStream.Subscriber subscriber) {
            }

            @Override
            public void unsubscribe(PluginSseStream.Subscriber subscriber) {
            }
        };
        pool = new OutputAttachPool(service, (nodeId, instanceId) -> backend);
        PluginSseStream stream = pool.open("node-1", "inst-1");

        PluginSseStream.Subscriber first = noopSubscriber();
        PluginSseStream.Subscriber second = noopSubscriber();
        stream.subscribe(first);
        assertTrue(attachDone.await(5, TimeUnit.SECONDS), "首个订阅应触发节点 attach");
        assertEquals(1, nodeCalls.stream().filter(m -> m.endsWith("output.subscribe")).count());

        // 第二个浏览器订阅：不重复 attach；第一个离开：不 detach（实例级泵共享）。
        stream.subscribe(second);
        stream.unsubscribe(first);
        Thread.sleep(150);
        assertEquals(1, nodeCalls.stream().filter(m -> m.endsWith("output.subscribe")).count());
        assertEquals(0, nodeCalls.stream().filter(m -> m.endsWith("output.unsubscribe")).count());

        // 最后一个订阅离开：异步 detach。
        stream.unsubscribe(second);
        assertTrue(detachDone.await(5, TimeUnit.SECONDS), "最后订阅离开才 detach");
        assertEquals(1, nodeCalls.stream().filter(m -> m.endsWith("output.unsubscribe")).count());
    }
}
