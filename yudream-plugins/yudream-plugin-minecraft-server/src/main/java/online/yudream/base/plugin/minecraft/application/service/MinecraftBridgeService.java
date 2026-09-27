package online.yudream.base.plugin.minecraft.application.service;

import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServer;
import online.yudream.base.plugin.minecraft.domain.repo.MinecraftServerRepository;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftBridgeSettings;
import online.yudream.base.plugin.spi.http.PluginSseStream;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.messaging.PluginEvent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessageContent;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingConnection;
import online.yudream.base.plugin.spi.system.messaging.PluginMessagingGroup;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 群服互联：游戏事件 → QQ 群、群聊消息 → 游戏。
 *
 * <p>出站由 {@link MinecraftBridgeListener} 回调驱动（进退服来自玩家活动记录流程，
 * 聊天/死亡/成就来自上报端点）；入站由宿主消息总线回调 {@link #onGroupMessage}，
 * 写入每台服务器的内存队列，MC 端桥接通过上报接口轮询拉取。
 *
 * <p>转发永远不能影响上报主流程：任何异常都折叠为日志。为防止桥接重试队列重放旧事件刷屏，
 * 只转发 5 分钟内的事件，并按（服务器+玩家+类型+时刻）去重。
 */
public class MinecraftBridgeService implements MinecraftBridgeListener {

    private static final Logger LOGGER = Logger.getLogger(MinecraftBridgeService.class.getName());

    /** 迟于该窗口的进退服事件视为桥接重放，不进群。 */
    static final long MAX_EVENT_AGE_MILLIS = 5L * 60 * 1000;
    /** 群消息进游戏后在该窗口内可被拉取，超时丢弃。 */
    static final long INBOUND_TTL_MILLIS = 10L * 60 * 1000;
    /** SSE 心跳周期：必须短于常见代理/客户端读超时，保持长连接不被中间设备掐断。 */
    static final long INBOUND_HEARTBEAT_MILLIS = 25L * 1000;
    private static final int INBOUND_BUFFER_CAPACITY = 200;
    private static final int MAX_INBOUND_CONTENT = 300;
    private static final int DEDUP_CAPACITY = 1024;
    private static final int ONLINE_NAMES_SHOWN = 3;

    private final MinecraftServerRepository repository;
    private final FrameworkServices framework;
    /** serverId → 在线玩家名列表；由装配层接到应用服务的在线活动查询上。 */
    private final Function<String, List<String>> onlinePlayers;
    private final LongSupplier clock;

    private final Map<String, MinecraftBridgeSettings> settingsCache = new ConcurrentHashMap<>();
    private final Map<String, Deque<InboundMessage>> inboundBuffers = new ConcurrentHashMap<>();
    private final AtomicLong inboundSequence = new AtomicLong();
    /** 每台游戏服务器待拉取的游标：客户端按 afterSeq 增量拉取。 */
    private final Map<String, Long> inboundCursors = new ConcurrentHashMap<>();
    /** 每台游戏服务器的 SSE 实时订阅：群消息写入队列时同步推送。 */
    private final Map<String, List<InboundStream>> inboundStreams = new ConcurrentHashMap<>();
    private final SetWithCapacity deduplication = new SetWithCapacity(DEDUP_CAPACITY);
    private volatile ScheduledExecutorService inboundHeartbeatExecutor;

    public MinecraftBridgeService(MinecraftServerRepository repository, FrameworkServices framework,
                                  Function<String, List<String>> onlinePlayers) {
        this(repository, framework, onlinePlayers, System::currentTimeMillis);
    }

    MinecraftBridgeService(MinecraftServerRepository repository, FrameworkServices framework,
                           Function<String, List<String>> onlinePlayers, LongSupplier clock) {
        this.repository = repository;
        this.framework = framework;
        this.onlinePlayers = onlinePlayers == null ? serverId -> List.of() : onlinePlayers;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 配置

    public MinecraftBridgeSettings settings(String serverId) {
        requireServer(serverId);
        return cachedSettings(serverId);
    }

    public MinecraftBridgeSettings saveSettings(MinecraftBridgeSettings updated) {
        MinecraftBridgeSettings normalized = updated.normalize(clock.getAsLong());
        normalized.validate();
        requireServer(normalized.serverId());
        MinecraftBridgeSettings saved = repository.saveBridgeSettings(normalized);
        settingsCache.remove(normalized.serverId());
        return saved;
    }

    public List<Map<String, Object>> connectionOptions() {
        try {
            List<Map<String, Object>> options = new ArrayList<>();
            for (PluginMessagingConnection connection : framework.messaging().connections()) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("id", connection.id());
                option.put("name", connection.name());
                option.put("platform", connection.platform());
                options.add(option);
            }
            return options;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "读取消息连接选项失败，按无可用连接处理", e);
            return List.of();
        }
    }

    public List<Map<String, Object>> groupOptions(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> options = new ArrayList<>();
            for (PluginMessagingGroup group : framework.messaging().groups(connectionId.trim())) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("id", group.id());
                option.put("name", group.name());
                options.add(option);
            }
            return options;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "读取群聊选项失败，按无可用群聊处理", e);
            return List.of();
        }
    }

    private MinecraftBridgeSettings cachedSettings(String serverId) {
        return settingsCache.computeIfAbsent(serverId, id ->
                repository.findBridgeSettings(id).orElseGet(() -> MinecraftBridgeSettings.empty(id)));
    }

    private MinecraftServer requireServer(String serverId) {
        return repository.findById(requireText(serverId, "服务器 ID 不能为空"))
                .orElseThrow(() -> new IllegalArgumentException("服务器不存在：" + serverId));
    }

    // ---------------------------------------------------------------- 出站：游戏 → 群

    @Override
    public void onPresence(String serverId, boolean join, String playerId, String playerName, long eventAt) {
        MinecraftBridgeSettings settings = cachedSettings(serverId);
        if (!settings.enabled() || !settings.forwardJoinQuit() || !settings.targetConfigured()) {
            return;
        }
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        String name = playerName.trim();
        if (staleOrDuplicate(serverId, "presence", (join ? "in:" : "out:") + playerId + "/" + name, eventAt)) {
            LOGGER.info("进退服通报跳过（" + name + "）：事件超龄（超过 5 分钟）或桥接重复帧，按设计不重复进群。");
            return;
        }
        String summary = onlineSummary(serverId);
        StringBuilder text = new StringBuilder();
        text.append(join ? "▶ " : "◀ ").append(name).append(join ? " 加入了服务器" : " 退出了服务器");
        if (!summary.isEmpty()) {
            text.append('\n').append(summary);
        }
        LOGGER.info("已投递进退服通报（" + name + (join ? " 进服" : " 退服") + "）。");
        send(settings, text.toString());
    }

    @Override
    public void onGameEvent(String serverId, GameEventKind kind, String playerName, String content, long eventAt) {
        if (kind == null || content == null || content.isBlank()) {
            return;
        }
        MinecraftBridgeSettings settings = cachedSettings(serverId);
        if (!settings.enabled() || !settings.targetConfigured() || !forwardEnabled(settings, kind)) {
            return;
        }
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        String name = playerName.trim();
        String text = switch (kind) {
            case CHAT -> "💬 " + name + "：" + clamp(content.trim(), MAX_INBOUND_CONTENT);
            case DEATH -> "💀 " + clamp(content.trim(), MAX_INBOUND_CONTENT);
            case ADVANCEMENT -> "🏆 " + name + " 达成了成就：" + clamp(content.trim(), MAX_INBOUND_CONTENT);
        };
        if (staleOrDuplicate(serverId, kind.name(), name + "/" + text, eventAt)) {
            return;
        }
        send(settings, text);
    }

    private boolean forwardEnabled(MinecraftBridgeSettings settings, GameEventKind kind) {
        return switch (kind) {
            case CHAT -> settings.forwardChat();
            case DEATH -> settings.forwardDeath();
            case ADVANCEMENT -> settings.forwardAdvancement();
        };
    }

    /** 面板实例电源状态迁移（running↔停止）：按开关投递启停通报（同态重复帧由去重兜底）。 */
    @Override
    public void onServerPowerState(String serverId, boolean started, long atMs) {
        MinecraftBridgeSettings settings = cachedSettings(serverId);
        if (!settings.enabled() || !settings.forwardStartStop() || !settings.targetConfigured()) {
            return;
        }
        if (staleOrDuplicate(serverId, "power", started ? "up" : "down", atMs)) {
            return;
        }
        LOGGER.info("已投递启停通报（" + (started ? "已启动" : "已关闭") + "）。");
        send(settings, started ? "🟢 服务器已启动，欢迎玩家进入。" : "🔴 服务器已关闭。");
    }

    /** 进退服消息尾行的在线概览：人数 + 前三个玩家名（超出部分用「等」收尾）。 */
    private String onlineSummary(String serverId) {
        List<String> names = onlinePlayers.apply(serverId).stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        if (names.isEmpty()) {
            return "当前没有玩家在线";
        }
        StringBuilder summary = new StringBuilder("当前在线 ").append(names.size()).append(" 人：");
        for (int i = 0; i < names.size() && i < ONLINE_NAMES_SHOWN; i++) {
            if (i > 0) {
                summary.append('、');
            }
            summary.append(names.get(i));
        }
        if (names.size() > ONLINE_NAMES_SHOWN) {
            summary.append(" 等");
        }
        return summary.toString();
    }

    private void send(MinecraftBridgeSettings settings, String text) {
        try {
            framework.messaging().sendToChannel(settings.connectionId(), settings.channelId(),
                            new PluginMessageContent(PluginMessageContent.Type.TEXT, withServerPrefix(settings.serverId(), text), null, null))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            LOGGER.warning("群服互联转发失败：server=" + settings.serverId()
                                    + " channel=" + settings.channelId() + " error=" + rootMessage(error));
                        }
                    });
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warning("群服互联转发不可用：server=" + settings.serverId() + " error=" + rootMessage(e));
        }
    }

    /** 群里的通报标注来源服务器（[服务器名]:原文）；查不到名称时退回原文，转发永不失败。 */
    private String withServerPrefix(String serverId, String text) {
        String name;
        try {
            name = repository.findById(serverId).map(MinecraftServer::name).orElse(null);
        } catch (RuntimeException e) {
            return text;
        }
        return name == null || name.isBlank() ? text : "[" + name + "]:" + text;
    }

    /** 超龄（桥接重放）或刚转发过（桥接至少一次投递的重复帧）的事件不再进群。 */
    private boolean staleOrDuplicate(String serverId, String kind, String identity, long eventAt) {
        long now = clock.getAsLong();
        if (eventAt > 0 && eventAt < now - MAX_EVENT_AGE_MILLIS) {
            return true;
        }
        return !deduplication.add(serverId + "|" + kind + "|" + identity + "|" + eventAt);
    }

    // ---------------------------------------------------------------- 入站：群 → 游戏

    /** 宿主消息总线回调：命中绑定群聊的普通文本消息进入各服务器的拉取队列。 */
    public void onGroupMessage(PluginEvent event) {
        if (event == null || event.userId() == null || event.userId().isBlank()
                || event.userId().equals(event.selfId())) {
            return;
        }
        String content = event.content() == null ? "" : event.content().trim();
        if (content.isEmpty() || content.startsWith("/")) {
            return;
        }
        if (event.connectionId() == null || event.connectionId().isBlank()
                || event.channelId() == null || event.channelId().isBlank()) {
            return;
        }
        for (String serverId : enabledServerIds()) {
            MinecraftBridgeSettings settings = cachedSettings(serverId);
            if (!settings.forwardToGame() || !settings.targetConfigured()) {
                continue;
            }
            if (!settings.connectionId().equals(event.connectionId())
                    || !settings.channelId().equals(event.channelId())) {
                continue;
            }
            enqueueInbound(serverId, event.userId(), content);
        }
    }

    private List<String> enabledServerIds() {
        List<String> serverIds = new ArrayList<>();
        int page = 1;
        while (true) {
            List<MinecraftServer> batch = repository.list(page, 200, false);
            for (MinecraftServer server : batch) {
                serverIds.add(server.id());
            }
            if (batch.size() < 200) {
                return serverIds;
            }
            page++;
        }
    }

    private void enqueueInbound(String serverId, String sender, String content) {
        Deque<InboundMessage> buffer = inboundBuffers.computeIfAbsent(serverId, key -> new ArrayDeque<>());
        InboundMessage message = new InboundMessage(
                inboundSequence.incrementAndGet(), clamp(sender, 64), clamp(content, MAX_INBOUND_CONTENT),
                clock.getAsLong());
        synchronized (buffer) {
            buffer.addLast(message);
            while (buffer.size() > INBOUND_BUFFER_CAPACITY) {
                buffer.removeFirst();
            }
        }
        fanOutInbound(serverId, message);
    }

    // ---------------------------------------------------------------- 入站 SSE 实时推送

    /**
     * MC 端桥接的 SSE 订阅视图：连接即重放 {@code afterSeq} 之后的缓冲增量，
     * 此后群消息写入队列时实时推送。轮询接口保留，作为旧桥接或不支持长连接
     * 环境的降级通道。
     */
    public PluginSseStream openInboundStream(String serverId, Long afterSeq) {
        requireServer(serverId);
        InboundStream stream = new InboundStream(serverId, afterSeq == null ? 0L : afterSeq);
        inboundStreams.computeIfAbsent(serverId, key -> new CopyOnWriteArrayList<>()).add(stream);
        ensureInboundHeartbeat();
        return stream;
    }

    /** 插件停用时关闭全部 SSE 订阅并停掉心跳，避免线程与连接泄漏。 */
    public void closeInboundStreams() {
        ScheduledExecutorService executor = inboundHeartbeatExecutor;
        inboundHeartbeatExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        for (List<InboundStream> streams : inboundStreams.values()) {
            for (InboundStream stream : streams) {
                stream.close();
            }
            streams.clear();
        }
        inboundStreams.clear();
    }

    private void fanOutInbound(String serverId, InboundMessage message) {
        List<InboundStream> streams = inboundStreams.get(serverId);
        if (streams == null || streams.isEmpty()) {
            return;
        }
        for (InboundStream stream : streams) {
            stream.push(message);
        }
    }

    private synchronized void ensureInboundHeartbeat() {
        if (inboundHeartbeatExecutor != null) {
            return;
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "minecraft-server-inbound-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tickInboundHeartbeat,
                INBOUND_HEARTBEAT_MILLIS, INBOUND_HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
        inboundHeartbeatExecutor = executor;
    }

    private void tickInboundHeartbeat() {
        long at = clock.getAsLong();
        for (List<InboundStream> streams : inboundStreams.values()) {
            for (InboundStream stream : streams) {
                stream.heartbeat(at);
            }
        }
    }

    /** 单条桥接连接的 SSE 视图；宿主在响应写出阶段回调 {@link #subscribe(Subscriber)}。 */
    private final class InboundStream implements PluginSseStream {

        private final String serverId;
        private final long afterSeq;
        private volatile Subscriber subscriber;

        private InboundStream(String serverId, long afterSeq) {
            this.serverId = serverId;
            this.afterSeq = afterSeq;
        }

        @Override
        public void subscribe(Subscriber subscriber) {
            this.subscriber = subscriber;
            try {
                subscriber.send("connected", java.util.Map.of("id", 0, "serverId", serverId,
                        "latest", latestBufferedSeq()));
                replay(subscriber);
            } catch (RuntimeException e) {
                detach();
            }
        }

        @Override
        public void unsubscribe(Subscriber subscriber) {
            detach();
        }

        private void replay(Subscriber subscriber) {
            Deque<InboundMessage> buffer = inboundBuffers.get(serverId);
            if (buffer == null) {
                return;
            }
            long now = clock.getAsLong();
            List<InboundMessage> replayed = new ArrayList<>();
            synchronized (buffer) {
                for (InboundMessage message : buffer) {
                    if (now - message.at() > INBOUND_TTL_MILLIS) {
                        continue;
                    }
                    if (message.seq() > afterSeq) {
                        replayed.add(message);
                    }
                }
            }
            for (InboundMessage message : replayed) {
                subscriber.send("message", inboundEnvelope(message));
            }
        }

        private void push(InboundMessage message) {
            Subscriber subscriber = this.subscriber;
            if (subscriber == null) {
                return;
            }
            try {
                subscriber.send("message", inboundEnvelope(message));
            } catch (RuntimeException e) {
                detach();
            }
        }

        private void heartbeat(long at) {
            Subscriber subscriber = this.subscriber;
            if (subscriber == null) {
                return;
            }
            try {
                subscriber.send("heartbeat", java.util.Map.of("id", 0, "at", at));
            } catch (RuntimeException e) {
                detach();
            }
        }

        /** 当前缓冲内的最大序号（含超龄消息，与轮询接口的 latest 口径一致）；空缓冲返回 afterSeq。 */
        private long latestBufferedSeq() {
            Deque<InboundMessage> buffer = inboundBuffers.get(serverId);
            long latest = afterSeq;
            if (buffer != null) {
                synchronized (buffer) {
                    for (InboundMessage message : buffer) {
                        latest = Math.max(latest, message.seq());
                    }
                }
            }
            return latest;
        }

        private void close() {
            Subscriber subscriber = this.subscriber;
            this.subscriber = null;
            if (subscriber != null) {
                try {
                    subscriber.complete();
                } catch (RuntimeException ignored) {
                    // 连接可能已断开
                }
            }
        }

        private void detach() {
            subscriber = null;
            List<InboundStream> streams = inboundStreams.get(serverId);
            if (streams != null) {
                streams.remove(this);
            }
        }
    }

    /** SSE 事件信封：id 字段供宿主写 SSE id 行，seq 供桥接推进游标。 */
    private static Map<String, Object> inboundEnvelope(InboundMessage message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", message.seq());
        envelope.put("seq", message.seq());
        envelope.put("sender", message.sender());
        envelope.put("content", message.content());
        envelope.put("at", message.at());
        return envelope;
    }

    /**
     * MC 端桥接轮询拉取群消息。{@code afterSeq} 为上次已取到的最大序号，返回其之后的全部消息；
     * 过期消息直接丢弃，不进入响应。
     */
    public InboundBatch drainInbound(String serverId, long afterSeq, int limit) {
        requireServer(serverId);
        Deque<InboundMessage> buffer = inboundBuffers.get(serverId);
        long cursor = Math.max(afterSeq, inboundCursors.getOrDefault(serverId, 0L));
        long now = clock.getAsLong();
        List<InboundMessage> messages = new ArrayList<>();
        long latest = cursor;
        if (buffer != null) {
            synchronized (buffer) {
                for (InboundMessage message : buffer) {
                    latest = Math.max(latest, message.seq());
                    if (now - message.at() > INBOUND_TTL_MILLIS) {
                        continue;
                    }
                    if (message.seq() > cursor && messages.size() < limit) {
                        messages.add(message);
                    }
                }
            }
        }
        long newCursor = messages.isEmpty() ? latest : messages.get(messages.size() - 1).seq();
        inboundCursors.put(serverId, Math.max(cursor, newCursor));
        return new InboundBatch(List.copyOf(messages), latest);
    }

    public record InboundMessage(long seq, String sender, String content, long at) {
    }

    public record InboundBatch(List<InboundMessage> messages, long latest) {
    }

    // ---------------------------------------------------------------- 工具

    private static String clamp(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** 有界去重集合：最旧的键先淘汰（只做防重放，不承担持久语义）。 */
    private static final class SetWithCapacity {
        private final LinkedHashMap<String, Boolean> keys;

        SetWithCapacity(int capacity) {
            this.keys = new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > capacity;
                }
            };
        }

        synchronized boolean add(String key) {
            if (keys.put(key, Boolean.TRUE) != null) {
                return false;
            }
            return true;
        }
    }
}
