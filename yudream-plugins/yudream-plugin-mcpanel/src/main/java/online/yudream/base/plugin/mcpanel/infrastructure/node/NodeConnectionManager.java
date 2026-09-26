package online.yudream.base.plugin.mcpanel.infrastructure.node;

import online.yudream.base.plugin.mcpanel.application.port.NodeControlPlane;
import online.yudream.base.plugin.mcpanel.domain.McpanelBusinessException;
import online.yudream.base.plugin.mcpanel.domain.aggregate.McpanelNode;
import online.yudream.base.plugin.mcpanel.domain.enumerate.NodeStatus;
import online.yudream.base.plugin.mcpanel.domain.repo.McpanelNodeRepository;
import online.yudream.base.plugin.mcpanel.domain.service.NodeEndpointPolicy;
import online.yudream.base.plugin.mcpanel.domain.valobj.NodeStatsSnapshot;
import online.yudream.base.plugin.mcpanel.infrastructure.support.NodeSecrets;
import online.yudream.base.plugin.mcpanel.infrastructure.support.StatsMapper;
import online.yudream.base.plugin.spi.http.PluginSseStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 面板 → 节点控制信道管理器（协议 v1 §3/§5）。
 *
 * 生命周期（generation）：每次拨号递增 generation；SocketListener 携带该代
 * 仅在代与 socket 匹配时生效，旧连接回调（onOpen/onText/onClose/onError）一律丢弃。
 * closeSocket 同步完成状态迁移（不等对端 close 帧）：cancel 拨号/hello future、
 * sendClose + abort 有界终止；disable/remove/shutdown 关闭后不再重拨。
 *
 * TLS：默认 PKIX/hostname；pinned 节点用 X509ExtendedTrustManager 仅校验叶证书
 * SHA-256 与管理员预配置 pin 相等（显式接管连接级校验，不做 hostname 匹配，
 * 不全局关闭校验）。HttpClient 按 pin 缓存并在插件关闭时统一 close。
 *
 * 帧处理：上限按 UTF-8 字节跨 fragment 累计（FrameAssembler）；每个 onText
 * 回调严格一次 request(1)；合法且通过校验的帧刷新可信 lastSeen——关联到未决
 * 调用的 res/err、载荷与 topic/seq 校验通过的 evt（含 hello res / node.stats /
 * 实例事件），malformed/unknown 帧一律不续命；evt seq 按 topic 单调去重，载荷
 * 缺失/越界忽略。实例事件绑定来源 nodeId（事件只投递给同节点订阅视图），不串节点。
 *
 * 出站：每 Conn 一条有界保序写帧队列（ConnWriteQueue）——只等字节写出、不等业务
 * 响应；写失败/写超时立即失败对应 pending 并关闭连接；断线/卸载/代际变更立即
 * 异常完成并清理全部 pending；hello 经队列异步发送；出站帧先做 UTF-8 256KB 本地
 * 检查；调用方超时后未发出的帧会被丢弃（避免界面已报超时而变更仍被发出）。
 */
public class NodeConnectionManager implements NodeControlPlane, AutoCloseable {

    enum State {DISCONNECTED, CONNECTING, ONLINE}

    private static final String CAP_NODE_HELLO = "node.hello";
    private static final String CAP_NODE_STATS = "node.stats";

    private final McpanelNodeRepository nodeRepository;
    private final NodeSecrets secrets;
    private final NodeEventBus eventBus;
    private final long offlineAfterMs;
    private final long pingPeriodMs;
    private final long tickPeriodMs;
    private final WebSocketDialer dialer;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Conn> connections = new ConcurrentHashMap<>();
    private final Map<String, HttpClient> pinnedClients = new ConcurrentHashMap<>();
    private final HttpClient defaultClient;
    private final AtomicLong threadCounter = new AtomicLong();
    private static final com.fasterxml.jackson.databind.ObjectMapper ERR_PAYLOAD_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> tickTask;
    private volatile boolean disposed;

    public NodeConnectionManager(McpanelNodeRepository nodeRepository, NodeSecrets secrets, NodeEventBus eventBus) {
        this(nodeRepository, secrets, eventBus, NodeProtocol.OFFLINE_AFTER_MS, 10_000L, 1_000L);
    }

    /** 测试可注入更短周期。 */
    public NodeConnectionManager(McpanelNodeRepository nodeRepository, NodeSecrets secrets, NodeEventBus eventBus,
                                 long offlineAfterMs, long pingPeriodMs, long tickPeriodMs) {
        this(nodeRepository, secrets, eventBus, offlineAfterMs, pingPeriodMs, tickPeriodMs, null);
    }

    /** 出站拨号通道（包可见）：生产实现走 JDK HttpClient，测试可注入受控替身。 */
    interface WebSocketDialer {

        CompletableFuture<WebSocket> dial(String nodeId, McpanelNode node, String secret,
                                          WebSocket.Listener listener);
    }

    NodeConnectionManager(McpanelNodeRepository nodeRepository, NodeSecrets secrets, NodeEventBus eventBus,
                          long offlineAfterMs, long pingPeriodMs, long tickPeriodMs, WebSocketDialer dialer) {
        this.nodeRepository = nodeRepository;
        this.secrets = secrets;
        this.eventBus = eventBus;
        this.offlineAfterMs = offlineAfterMs;
        this.pingPeriodMs = pingPeriodMs;
        this.tickPeriodMs = tickPeriodMs;
        this.dialer = dialer != null ? dialer : this::dialOverHttpClient;
        this.defaultClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    private CompletableFuture<WebSocket> dialOverHttpClient(String nodeId, McpanelNode node, String secret,
                                                            WebSocket.Listener listener) {
        HttpClient client = clientFor(node);
        return client.newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + secret)
                .header("X-Mcpanel-Node-Id", nodeId)
                .buildAsync(NodeEndpointPolicy.controlUri(node.endpoint()), listener);
    }

    /** pinned 模式按证书指纹复用专用 client；默认 PKIX/hostname 走共享 client。 */
    private HttpClient clientFor(McpanelNode node) {
        if (!NodeEndpointPolicy.TLS_PINNED.equals(node.tlsMode()) || node.pinSha256() == null) {
            return defaultClient;
        }
        return pinnedClients.computeIfAbsent(node.pinSha256(), pin -> {
            try {
                TrustManager[] trustManagers = new TrustManager[]{new PinTrustManager(pin)};
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustManagers, null);
                return HttpClient.newBuilder()
                        .sslContext(context)
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .build();
            } catch (Exception error) {
                throw new IllegalStateException("pinned SSLContext 构建失败", error);
            }
        });
    }

    public synchronized void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcpanel-node-conn-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        tickTask = scheduler.scheduleWithFixedDelay(this::tick, tickPeriodMs, tickPeriodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        disposed = true;
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        for (Conn conn : connections.values()) {
            conn.close(0L, NodeProtocol.CLOSE_NORMAL, "plugin shutdown", false);
        }
        connections.clear();
        // HttpClient.close() 可能等待未完成 HTTP future；先 cancel 全部连接再用
        // shutdownNow + 有界 await 终止，保证插件卸载不被阻塞。
        shutdownClient(defaultClient);
        for (HttpClient client : pinnedClients.values()) {
            shutdownClient(client);
        }
        pinnedClients.clear();
    }

    private static void shutdownClient(HttpClient client) {
        try {
            client.shutdownNow();
        } catch (RuntimeException ignored) {
            // 已关闭
        }
        try {
            client.awaitTermination(java.time.Duration.ofSeconds(1));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void syncNode(McpanelNode node) {
        if (disposed) {
            // disposed 门闸：插件关闭后不得再建 Conn/重连。
            return;
        }
        Conn conn = connections.computeIfAbsent(node.id(), id -> new Conn(id));
        boolean configChanged = conn.applyConfig(node);
        if (!node.enabled()) {
            conn.close(conn.generation(), NodeProtocol.CLOSE_NORMAL, "node disabled", false);
            return;
        }
        if (configChanged) {
            // endpoint/tls/pin 变化：断开现连，按新配置立即重拨。
            conn.close(conn.generation(), NodeProtocol.CLOSE_NORMAL, "node config changed", true);
            conn.requestDialAt(System.currentTimeMillis());
            return;
        }
        conn.requestDialAt(System.currentTimeMillis());
    }

    @Override
    public void removeNode(String nodeId) {
        Conn conn = connections.remove(nodeId);
        if (conn != null) {
            conn.close(conn.generation(), NodeProtocol.CLOSE_NORMAL, "node removed", false);
        }
    }

    @Override
    public NodeRuntime runtime(String nodeId) {
        Conn conn = connections.get(nodeId);
        if (conn == null) {
            return NodeRuntime.empty();
        }
        return new NodeRuntime(conn.connected(), conn.online(), conn.trustedLastSeenMs(), conn.snapshot());
    }

    @Override
    public PluginSseStream openEventStream(String nodeId, Long afterEventId) {
        return eventBus.open(nodeId, afterEventId);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                                            Map<String, Object> payload) {
        return call(nodeId, method, payload, NodeProtocol.REQ_TIMEOUT_MS);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Map<String, Object>> call(String nodeId, String method,
                                                                            Map<String, Object> payload, long timeoutMs) {
        Conn conn = connections.get(nodeId);
        if (conn == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new NodeCallException("node.offline", "节点未注册连接"));
        }
        return conn.call(method, payload, timeoutMs);
    }

    @Override
    public List<String> caps(String nodeId) {
        Conn conn = connections.get(nodeId);
        return conn == null ? List.of() : conn.caps();
    }

    private void tick() {
        if (disposed) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (Conn conn : connections.values()) {
                try {
                    conn.tick(now);
                } catch (RuntimeException ignored) {
                    // 单个连接异常不影响整体调度
                }
            }
        } catch (RuntimeException ignored) {
            // 调度体不抛出
        }
    }

    // ---------- 连接状态 ----------

    private final class Conn {

        private final String nodeId;
        private final Object lock = new Object();
        private final FrameAssembler assembler = new FrameAssembler();
        private final Map<String, CompletableFuture<NodeProtocol.Envelope>> pending = new ConcurrentHashMap<>();
        /** evt seq 按 topic 单调去重（协议 §7）；断线/换代时清空。 */
        private final Map<String, Long> lastSeqByTopic = new HashMap<>();

        private long generation;
        private State state = State.DISCONNECTED;
        private McpanelNode config;
        private WebSocket socket;
        private CompletableFuture<WebSocket> dialFuture;
        private CompletableFuture<NodeProtocol.Envelope> helloFuture;
        private ConnWriteQueue writeQueue = ConnWriteQueue.dead();
        private long trustedLastSeen;
        private long nextAttemptAtMs;
        private long lastPingAtMs;
        private int backoffAttempt;
        private NodeStatsSnapshot snapshot;
        private volatile List<String> caps = List.of();
        /** 出站帧上限：节点 hello.maxFrame 协商（0.6.1+ 为 4MiB）；旧节点缺省 256KB。 */
        private volatile int outboundFrameBytes = NodeProtocol.MAX_FRAME_BYTES;

        /** 面板→节点通用调用：caps 门禁 + req/res 关联 + 超时；payload 内携带 ik。 */
        java.util.concurrent.CompletableFuture<Map<String, Object>> call(String method, Map<String, Object> payload) {
            return call(method, payload, NodeProtocol.REQ_TIMEOUT_MS);
        }

        java.util.concurrent.CompletableFuture<Map<String, Object>> call(String method, Map<String, Object> payload, long timeoutMs) {
            Map<String, Object> body = payload == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(payload);
            CompletableFuture<Map<String, Object>> future = new java.util.concurrent.CompletableFuture<>();
            NodeProtocol.Envelope request = NodeProtocol.Envelope.request(method, body);
            CompletableFuture<NodeProtocol.Envelope> envelopeFuture;
            ConnWriteQueue queue;
            // 登记 pending 与状态/代际判定同一临界区：close/failAll 与 put 不再交错，
            // 登记后的任何断线都会立刻失败该 pending，而不是滞留到超时。
            synchronized (lock) {
                if (state != State.ONLINE) {
                    future.completeExceptionally(new NodeCallException("node.offline", "节点未在线"));
                    return future;
                }
                if (!caps.contains(method)) {
                    future.completeExceptionally(new NodeCallException("node.capability", "节点不支持 " + method));
                    return future;
                }
                envelopeFuture = new CompletableFuture<>();
                envelopeFuture.whenComplete((envelope, error) -> {
                    // 完成（含超时）即摘除登记：迟到响应按未知 id 丢弃，避免 pending 积压。
                    pending.remove(request.id());
                    if (error != null) {
                        future.completeExceptionally(error);
                        return;
                    }
                    future.complete(envelope.p() == null ? Map.of() : envelope.p());
                });
                envelopeFuture.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
                pending.put(request.id(), envelopeFuture);
                queue = writeQueue;
            }
            // 出站帧本地检查（协议 §4）：超限不发 socket，本地按 proto.badFrame 失败。
            // 上限取节点 hello.maxFrame 协商值（0.6.1+ 为 4MiB）；旧节点 256KB——
            // 大安装计划（换源 urls 展开后）在旧节点上会命中此错误，升级节点即解除。
            String frame = NodeProtocol.encode(request);
            int limit = this.outboundFrameBytes;
            if (FrameAssembler.utf8Length(frame) > limit) {
                pending.remove(request.id());
                future.completeExceptionally(new NodeCallException("proto.badFrame",
                        "出站帧超过上限 " + (limit / 1024) + "KB（节点 0.6.1 以下默认 256KB，升级节点程序即可放宽）"));
                return future;
            }
            ConnWriteQueue.QueuedWrite wrote = queue.write(frame);
            // 调用方超时/放弃后，尚未发出的帧必须丢弃：不允许界面已报超时而变更仍被发出。
            envelopeFuture.whenComplete((envelope, error) -> {
                if (error != null) {
                    queue.discard(wrote);
                }
            });
            wrote.whenComplete((nil, error) -> {
                if (error == null || envelopeFuture.isDone() || wrote.isDiscarded()) {
                    // 已写出（等 res/err 或超时收尾）；或调用方已先行完成（超时/断线）。
                    return;
                }
                pending.remove(request.id());
                future.completeExceptionally(error instanceof NodeCallException nodeError
                        ? nodeError : NodeCallException.wrapSendFailure(error));
                // 异步写失败（如缓冲淤积后断链）：连接不可写，立即关闭并全部失败 pending。
                close(generation(), NodeProtocol.CLOSE_POLICY_VIOLATION, "send failed", true);
            });
            return future;
        }

        List<String> caps() {
            return caps;
        }

        private Conn(String nodeId) {
            this.nodeId = nodeId;
        }

        long generation() {
            synchronized (lock) {
                return generation;
            }
        }

        boolean connected() {
            synchronized (lock) {
                return socket != null || state == State.CONNECTING;
            }
        }

        boolean online() {
            synchronized (lock) {
                return state == State.ONLINE;
            }
        }

        long trustedLastSeenMs() {
            synchronized (lock) {
                return trustedLastSeen;
            }
        }

        NodeStatsSnapshot snapshot() {
            synchronized (lock) {
                return snapshot;
            }
        }

        boolean applyConfig(McpanelNode node) {
            synchronized (lock) {
                McpanelNode previous = config;
                config = node;
                if (previous == null) {
                    return false;
                }
                return !String.valueOf(previous.endpoint()).equals(node.endpoint())
                        || !String.valueOf(previous.tlsMode()).equals(node.tlsMode())
                        || !String.valueOf(previous.pinSha256()).equals(node.pinSha256())
                        || previous.localDevelopment() != node.localDevelopment();
            }
        }

        void requestDialAt(long atMs) {
            synchronized (lock) {
                if (state == State.ONLINE || state == State.CONNECTING) {
                    return;
                }
                nextAttemptAtMs = atMs;
            }
        }

        void tick(long now) {
            McpanelNode node;
            State current;
            synchronized (lock) {
                node = config;
                current = state;
            }
            if (node == null || !node.enabled()) {
                return;
            }
            if (current == State.ONLINE) {
                boolean timedOut;
                boolean duePing;
                synchronized (lock) {
                    timedOut = now - trustedLastSeen >= offlineAfterMs;
                    duePing = now - lastPingAtMs >= pingPeriodMs;
                    if (duePing && !timedOut) {
                        lastPingAtMs = now;
                    }
                }
                if (timedOut) {
                    close(generation(), NodeProtocol.CLOSE_NORMAL,
                            "no trusted frame within " + offlineAfterMs + "ms", true);
                    return;
                }
                WebSocket currentSocket = socket();
                if (duePing && currentSocket != null) {
                    try {
                        currentSocket.sendPing(ByteBuffer.allocate(0));
                    } catch (RuntimeException ignored) {
                        // ping 失败交给下一轮探测
                    }
                }
                return;
            }
            boolean due;
            synchronized (lock) {
                due = state == State.DISCONNECTED && now >= nextAttemptAtMs;
            }
            if (due) {
                // 拨号前读存储最新聚合：bootstrap 成功/enabled/endpoint 变更无需额外通知
                // 即在下一轮退避重试生效（每次实际拨号仅一次 findById，非全库扫描）。
                McpanelNode latest = nodeRepository.findById(nodeId).orElse(null);
                if (latest == null) {
                    // 节点已被删除：摘除连接，等待 removeNode 收尾。
                    connections.remove(nodeId);
                    return;
                }
                applyConfig(latest);
                if (!latest.enabled()) {
                    return;
                }
                dial(latest);
            }
        }

        private WebSocket socket() {
            synchronized (lock) {
                return socket;
            }
        }

        void dial(McpanelNode node) {
            if (!node.enrolled() || disposed) {
                // 未完成 bootstrap 的节点没有有效凭据会话，不拨号（避免必然 401 的空转）；
                // disposed 后一律不再新建连接。两者均按退避静默等待。
                scheduleRetry();
                return;
            }
            Optional<String> secret = secrets.secret(nodeId);
            if (secret.isEmpty()) {
                // 尚未 bootstrap：无凭据不拨号，按退避等待注册完成。
                scheduleRetry();
                return;
            }
            // DNS 级目标校验：管理员配置的主机名也必须在拨号时通过定类。
            try {
                NodeEndpointPolicy.validateResolvedTargets(node.endpoint(), node.localDevelopment());
            } catch (McpanelBusinessException denied) {
                scheduleRetry();
                return;
            }
            final long gen;
            synchronized (lock) {
                gen = ++generation;
                assembler.reset();
                lastSeqByTopic.clear();
                state = State.CONNECTING;
            }
            try {
                CompletableFuture<WebSocket> future =
                        dialer.dial(nodeId, node, secret.get(), new SocketListener(this, gen));
                synchronized (lock) {
                    dialFuture = future;
                }
                future.whenComplete((websocket, error) -> {
                    if (gen != generation()) {
                        // 过期拨号：直接终止，不触碰当前状态。
                        if (error == null && websocket != null) {
                            websocket.abort();
                        }
                        return;
                    }
                    if (error != null) {
                        onConnectFailure(gen, error);
                    }
                });
            } catch (RuntimeException error) {
                onConnectFailure(gen, error);
            }
        }

        void onConnectFailure(long gen, Throwable error) {
            if (!beginClose(gen, "handshake failed")) {
                return;
            }
            finishClose(gen, false, "handshake failed", true);
        }

        // ---------- 状态迁移（generation 保护） ----------

        /** 进入关闭流程：gen 匹配才迁移；返回是否真正执行。 */
        private boolean beginClose(long gen, String reason) {
            ConnWriteQueue queueToFail;
            synchronized (lock) {
                if (gen != generation || state == State.DISCONNECTED && socket == null && dialFuture == null) {
                    return false;
                }
                generation++;
                dialFuture = null;
                if (helloFuture != null) {
                    helloFuture.cancel(true);
                    helloFuture = null;
                }
                // 断线/换代/卸载：立即异常完成并清理全部 pending（含 hello），不再静默丢弃。
                failPendingLocked("node.offline", "连接已断开");
                queueToFail = writeQueue;
                assembler.reset();
                lastSeqByTopic.clear();
                socket = null;
            }
            if (queueToFail != null) {
                queueToFail.failAll(new NodeCallException("node.offline", "连接已断开"));
            }
            return true;
        }

        /** 总线锁内调用：立即异常完成全部未决调用并摘除登记。 */
        private void failPendingLocked(String code, String message) {
            if (pending.isEmpty()) {
                return;
            }
            java.util.List<CompletableFuture<NodeProtocol.Envelope>> waiting =
                    new java.util.ArrayList<>(pending.values());
            pending.clear();
            NodeCallException failure = new NodeCallException(code, message);
            for (CompletableFuture<NodeProtocol.Envelope> waitingFuture : waiting) {
                waitingFuture.completeExceptionally(failure);
            }
        }

        /** 完成关闭：同步落库/广播/重排拨号（不等对端 close 帧）。 */
        private void finishClose(long gen, boolean wasOnlineFlag, String reason, boolean retry) {
            boolean wasOnline;
            synchronized (lock) {
                wasOnline = state == State.ONLINE;
                state = State.DISCONNECTED;
                trustedLastSeen = 0L;
                snapshot = wasOnline ? snapshot : snapshot;
            }
            if (wasOnline || wasOnlineFlag) {
                long now = System.currentTimeMillis();
                nodeRepository.findById(nodeId).ifPresent(node -> nodeRepository.save(node.withRuntime(
                        NodeStatus.OFFLINE, node.agentVersion(), node.reportedHost(), null,
                        node.caps(), node.dockerVersion(), node.lastStats(),
                        node.lastSeenAtMs(), now)));
                if (wasOnline) {
                    Map<String, Object> event = new java.util.LinkedHashMap<>();
                    event.put("status", "offline");
                    event.put("reason", reason == null ? "" : reason);
                    eventBus.publish(NodeEventBus.TYPE_NODE_STATE, nodeId, event);
                }
            }
            if (retry) {
                scheduleRetry();
            }
        }

        /** 有界关闭：cancel future → sendClose+abort → 同步状态迁移。 */
        void close(long gen, int statusCode, String reason, boolean retry) {
            CompletableFuture<WebSocket> dial;
            WebSocket current;
            synchronized (lock) {
                if (gen != generation) {
                    return;
                }
                dial = dialFuture;
                current = socket;
            }
            if (dial != null) {
                dial.cancel(true);
            }
            if (!beginClose(gen, reason)) {
                // beginClose 幂等保护：同代重复关闭只走一次状态迁移
                synchronized (lock) {
                    if (gen != generation) {
                        return;
                    }
                }
            }
            if (current != null) {
                try {
                    current.sendClose(statusCode, reason == null ? "" : reason);
                } catch (RuntimeException ignored) {
                    // 连接已死
                }
                try {
                    current.abort();
                } catch (RuntimeException ignored) {
                    // 有界终止
                }
            }
            finishClose(gen, false, reason, retry);
        }

        private void scheduleRetry() {
            synchronized (lock) {
                long base = Math.min(
                        NodeProtocol.RECONNECT_BACKOFF_BASE_MS * (1L << Math.min(backoffAttempt, 5)),
                        NodeProtocol.RECONNECT_BACKOFF_MAX_MS);
                long jitter = random.nextInt(500);
                nextAttemptAtMs = System.currentTimeMillis() + base + jitter;
                backoffAttempt++;
            }
        }

        // ---------- 帧与回调（由 SocketListener 以 gen 调用） ----------

        void onOpen(long gen, WebSocket webSocket) {
            synchronized (lock) {
                if (gen != generation) {
                    webSocket.abort();
                    return;
                }
                socket = webSocket;
                // 每代新建写队列：严格保序、只等写出、失败立即传播。
                writeQueue = new ConnWriteQueue(webSocket::sendText,
                        NodeProtocol.OUTBOUND_QUEUE_CAPACITY, NodeProtocol.OUTBOUND_WRITE_TIMEOUT_MS,
                        () -> scheduler);
            }
            NodeProtocol.Envelope hello = NodeProtocol.Envelope.request(
                    NodeProtocol.M_NODE_HELLO, Map.of("panelVersion", NodeProtocol.PANEL_VERSION));
            CompletableFuture<NodeProtocol.Envelope> future = new CompletableFuture<>();
            ConnWriteQueue queue;
            synchronized (lock) {
                if (gen != generation) {
                    webSocket.abort();
                    return;
                }
                helloFuture = future;
                pending.put(hello.id(), future);
                queue = writeQueue;
            }
            future.orTimeout(NodeProtocol.REQ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .whenComplete((envelope, error) -> {
                        if (error != null && gen == generation()) {
                            pending.remove(hello.id());
                            close(gen, NodeProtocol.CLOSE_POLICY_VIOLATION, "hello timeout", true);
                        }
                    });
            // hello 经队列异步发送：写完成前队列不放行后续帧；写失败立即按握手失败收尾。
            String frame = NodeProtocol.encode(hello);
            if (FrameAssembler.utf8Length(frame) > NodeProtocol.MAX_FRAME_BYTES) {
                close(gen, NodeProtocol.CLOSE_POLICY_VIOLATION, "hello frame too large", true);
                return;
            }
            ConnWriteQueue.QueuedWrite wrote = queue.write(frame);
            wrote.whenComplete((nil, error) -> {
                if (error != null && !wrote.isDiscarded() && gen == generation()) {
                    close(gen, NodeProtocol.CLOSE_POLICY_VIOLATION, "hello send failed", true);
                }
            });
        }

        void onText(long gen, WebSocket webSocket, CharSequence data, boolean last) {
            if (gen != generation()) {
                return;
            }
            String frame = null;
            boolean tooBig = false;
            synchronized (lock) {
                if (gen != generation) {
                    return;
                }
                assembler.append(data);
                if (assembler.overLimit(NodeProtocol.MAX_FRAME_BYTES)) {
                    tooBig = true;
                } else if (last) {
                    frame = assembler.complete(last).orElse(null);
                }
            }
            if (tooBig) {
                close(gen, NodeProtocol.CLOSE_TOO_BIG, "frame exceeds 256KB", true);
                return;
            }
            if (frame != null) {
                handleFrame(gen, frame);
            }
        }

        void onClose(long gen, WebSocket webSocket, int statusCode, String reason) {
            close(gen, statusCode, "closed by peer: " + statusCode, true);
        }

        void onError(long gen, WebSocket webSocket, Throwable error) {
            close(gen, NodeProtocol.CLOSE_POLICY_VIOLATION, "socket error", true);
        }

        private void handleFrame(long gen, String frame) {
            Optional<NodeProtocol.Envelope> decoded = NodeProtocol.decode(frame);
            if (decoded.isEmpty()) {
                // proto.badFrame：不计离线、不刷新可信 lastSeen。
                return;
            }
            NodeProtocol.Envelope envelope = decoded.get();
            switch (envelope.t()) {
                case NodeProtocol.T_RES -> handleResponse(gen, envelope);
                case NodeProtocol.T_EVT -> handleEvent(gen, envelope);
                case NodeProtocol.T_ERR -> {
                    CompletableFuture<NodeProtocol.Envelope> future =
                            envelope.id() == null ? null : pending.remove(envelope.id());
                    if (future != null) {
                        // 合法关联 err：可信活动，可续命。
                        refreshActivity(gen);
                        // err 帧载荷 = {code,message,retryable}（协议 §6）：解析后按业务异常
                        // 透传人话（如"请先停止实例"），而不是把 JSON 原文拼进提示。
                        future.completeExceptionally(NodeProtocol.parseErrFrame(envelope));
                    }
                }
                default -> {
                    // 未知帧类型：忽略，不续命
                }
            }
        }

        private void handleResponse(long gen, NodeProtocol.Envelope envelope) {
            if (envelope.id() == null) {
                return;
            }
            CompletableFuture<NodeProtocol.Envelope> future = pending.remove(envelope.id());
            if (future == null) {
                // 迟到/未知 id：不续命。
                return;
            }
            // 合法关联 res：可信活动，可续命。
            refreshActivity(gen);
            if (NodeProtocol.M_NODE_HELLO.equals(envelope.m())) {
                String invalid = helloProblem(envelope.p());
                if (invalid != null) {
                    future.completeExceptionally(new IllegalStateException("invalid hello: " + invalid));
                    close(gen, NodeProtocol.CLOSE_POLICY_VIOLATION, invalid, true);
                    return;
                }
                markOnline(gen, envelope.p());
            }
            future.complete(envelope);
        }

        /** 合法关联帧续命：仅 ONLINE 且代际未变时刷新可信 lastSeen。 */
        private void refreshActivity(long gen) {
            synchronized (lock) {
                if (gen == generation && state == State.ONLINE) {
                    trustedLastSeen = System.currentTimeMillis();
                }
            }
        }

        /**
         * evt seq 收账（协议 §7）：回退（≤该 topic 已见最大值）的帧必须丢弃；
         * 无 seq 的 evt 兼容放行但不记录。gen 失配按过期帧处理。
         */
        private boolean seqAccept(long gen, NodeProtocol.Envelope envelope) {
            if (envelope.seq() == null) {
                return true;
            }
            synchronized (lock) {
                if (gen != generation) {
                    return false;
                }
                Long last = lastSeqByTopic.get(envelope.m());
                if (last != null && envelope.seq() <= last) {
                    return false;
                }
                lastSeqByTopic.put(envelope.m(), envelope.seq());
                return true;
            }
        }

        /** 协议 §5.1 载荷校验：nodeId 必须一致，sessionId/agentVersion 必填，caps 含基线两项。 */
        private String helloProblem(Map<String, Object> payload) {
            Object reportedNodeId = payload.get("nodeId");
            if (reportedNodeId == null || !nodeId.equals(String.valueOf(reportedNodeId))) {
                return "hello nodeId mismatch";
            }
            if (isBlank(payload.get("sessionId")) || isBlank(payload.get("agentVersion"))) {
                return "hello missing sessionId/agentVersion";
            }
            if (!(payload.get("caps") instanceof List<?> caps)
                    || !caps.contains(CAP_NODE_HELLO) || !caps.contains(CAP_NODE_STATS)) {
                return "hello caps missing baseline";
            }
            return null;
        }

        private void handleEvent(long gen, NodeProtocol.Envelope envelope) {
            if (state() != State.ONLINE) {
                return;
            }
            String topic = envelope.m();
            if (NodeProtocol.T_NODE_STATS.equals(topic)) {
                handleStatsEvent(gen, envelope);
                return;
            }
            // 已知 topic 才处理；载荷关键字段缺失按 proto.badPayload 忽略，不续命。
            boolean valid;
            switch (topic) {
                case "instance.state" ->
                        valid = hasNonBlank(envelope.p(), "instanceId") && hasNonBlank(envelope.p(), "state");
                case "instance.output" ->
                        valid = hasNonBlank(envelope.p(), "instanceId")
                                && envelope.p() != null && envelope.p().get("text") != null;
                case "node.terminal.output" -> valid = hasNonBlank(envelope.p(), "terminalId");
                case "ftp.opened", "ftp.closed" -> valid = envelope.p() != null;
                default -> {
                    return;
                }
            }
            if (!valid) {
                return;
            }
            if (!seqAccept(gen, envelope)) {
                return;
            }
            // 载荷与 topic/seq 均合法：可信活动，可续命。
            refreshActivity(gen);
            // 事件绑定来源 nodeId：只投递给同节点订阅视图，不串节点。
            eventBus.publish(topic, nodeId, envelope.p() == null ? Map.of() : envelope.p());
        }

        private void handleStatsEvent(long gen, NodeProtocol.Envelope envelope) {
            // seq 单调去重（协议 §7：接收方仅用于排序/去重，乱序不报错）。
            if (!seqAccept(gen, envelope)) {
                return;
            }
            Map<String, Object> payload = envelope.p();
            if (!validStats(payload)) {
                // proto.badPayload：忽略，不刷新可信 lastSeen。
                return;
            }
            long now = System.currentTimeMillis();
            synchronized (lock) {
                if (gen != generation) {
                    return;
                }
                trustedLastSeen = now;
            }
            String fallbackAgent = config() == null ? null : config().agentVersion();
            NodeStatsSnapshot stats = StatsMapper.fromWire(payload, now, fallbackAgent);
            setSnapshot(stats);
            nodeRepository.findById(nodeId).ifPresent(node -> nodeRepository.save(node.withRuntime(
                    NodeStatus.ONLINE, node.agentVersion(), node.reportedHost(), node.sessionId(),
                    node.caps(), stats.dockerVersion() == null ? node.dockerVersion() : stats.dockerVersion(),
                    stats, now, now)));
            eventBus.publish(NodeEventBus.TYPE_NODE_STATS, nodeId, StatsViewsBridge.toMap(stats));
        }

        private void markOnline(long gen, Map<String, Object> payload) {
            long now;
            String agentVersion;
            String sessionId;
            synchronized (lock) {
                if (gen != generation || state == State.ONLINE) {
                    return;
                }
                state = State.ONLINE;
                backoffAttempt = 0;
                trustedLastSeen = now = System.currentTimeMillis();
                lastPingAtMs = 0;
                agentVersion = text(payload.get("agentVersion"));
                sessionId = text(payload.get("sessionId"));
            }
            String hostname = text(payload.get("hostname"));
            String dockerVersion = text(payload.get("dockerVersion"));
            List<String> caps = payload.get("caps") instanceof List<?> rows
                    ? rows.stream().map(String::valueOf).toList() : List.of();
            this.caps = caps;
            // 节点 0.6.1+ 在 hello 上报自身单帧上限；旧节点缺省 256KB（协议 §4）。
            if (payload.get("maxFrame") instanceof Number advertised
                    && advertised.longValue() >= NodeProtocol.MAX_FRAME_BYTES
                    && advertised.longValue() <= 16L * 1024 * 1024) {
                this.outboundFrameBytes = (int) Math.min(advertised.longValue(), Integer.MAX_VALUE);
            }
            nodeRepository.findById(nodeId).ifPresent(node -> nodeRepository.save(node.withRuntime(
                    NodeStatus.ONLINE, agentVersion, hostname, sessionId, caps, dockerVersion,
                    node.lastStats(), now, now)));
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("status", "online");
            event.put("agentVersion", agentVersion);
            event.put("sessionId", sessionId);
            eventBus.publish(NodeEventBus.TYPE_NODE_STATE, nodeId, event);
        }

        private boolean validStats(Map<String, Object> payload) {
            if (payload == null || payload.isEmpty()) {
                return false;
            }
            Object cpu = payload.get("cpuPercent");
            if (!(cpu instanceof Number cpuNumber)
                    || cpuNumber.doubleValue() < 0d || cpuNumber.doubleValue() > 100d) {
                return false;
            }
            Object memTotal = payload.get("memTotalMb");
            if (!(memTotal instanceof Number memTotalNumber) || memTotalNumber.longValue() < 0L) {
                return false;
            }
            if (payload.containsKey("containers") && !(payload.get("containers") instanceof List<?>)) {
                return false;
            }
            return true;
        }

        // 最小化锁内读取的助手

        private State state() {
            synchronized (lock) {
                return state;
            }
        }

        private static boolean hasNonBlank(Map<String, Object> payload, String key) {
            return payload != null && !isBlank(payload.get(key));
        }

        private McpanelNode config() {
            synchronized (lock) {
                return config;
            }
        }

        private void setSnapshot(NodeStatsSnapshot stats) {
            synchronized (lock) {
                snapshot = stats;
            }
        }

        private static boolean isBlank(Object value) {
            return value == null || String.valueOf(value).isBlank();
        }

        private static String text(Object value) {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            return text.isBlank() ? null : text;
        }
    }

    /** 每次拨号一个 listener 实例，携带该代 generation；回调先验代再进状态机。 */
    private static final class SocketListener implements WebSocket.Listener {

        private final Conn conn;
        private final long generation;

        private SocketListener(Conn conn, long generation) {
            this.conn = conn;
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            conn.onOpen(generation, webSocket);
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                conn.onText(generation, webSocket, data, last);
            } finally {
                // 每个回调恰好续一次 demand，fragmented 消息不再停读。
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            conn.onClose(generation, webSocket, statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            conn.onError(generation, webSocket, error);
        }
    }

    /**
     * pinned 信任管理器：显式接管连接级校验——叶证书 DER SHA-256 与预配置 pin 相等，
     * 不做 hostname 匹配（自签证书 SAN 与 endpoint 不符仍可连通）；其余校验一律拒绝。
     */
    private static final class PinTrustManager extends X509ExtendedTrustManager {

        private final String pin;

        private PinTrustManager(String pin) {
            this.pin = pin;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("面板不作为 TLS 服务端");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("面板不作为 TLS 服务端");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType,
                                       javax.net.ssl.SSLEngine engine) throws CertificateException {
            throw new CertificateException("面板不作为 TLS 服务端");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            verifyPin(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            verifyPin(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType,
                                       javax.net.ssl.SSLEngine engine) throws CertificateException {
            verifyPin(chain);
        }

        private void verifyPin(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("节点返回空证书链");
            }
            try {
                byte[] der = chain[0].getEncoded();
                String sha256 = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(der));
                if (!sha256.equalsIgnoreCase(pin)) {
                    throw new CertificateException("节点叶证书指纹与预配置 pin 不一致");
                }
            } catch (CertificateException error) {
                throw error;
            } catch (Exception error) {
                throw new CertificateException("证书指纹计算失败");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** 桥接 StatsViews（application 层），避免 infrastructure 直依 application 实现。 */
    private static final class StatsViewsBridge {

        private StatsViewsBridge() {
        }

        static Map<String, Object> toMap(NodeStatsSnapshot snapshot) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("cpuPercent", snapshot.cpuPercent());
            map.put("memUsedMb", snapshot.memUsedMb());
            map.put("memTotalMb", snapshot.memTotalMb());
            map.put("diskUsedGb", snapshot.diskUsedGb());
            map.put("diskTotalGb", snapshot.diskTotalGb());
            map.put("load", snapshot.load());
            List<Map<String, Object>> containers = new java.util.ArrayList<>();
            for (NodeStatsSnapshot.ContainerStat container : snapshot.containers()) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("instanceId", container.instanceId());
                item.put("state", container.state());
                item.put("cpuPercent", container.cpuPercent());
                item.put("memUsedMb", container.memUsedMb());
                containers.add(item);
            }
            map.put("containers", containers);
            map.put("dockerVersion", snapshot.dockerVersion());
            map.put("agentVersion", snapshot.agentVersion());
            map.put("reportedAt", snapshot.reportedAtMs());
            return map;
        }
    }
}
