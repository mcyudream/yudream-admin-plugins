package online.yudream.base.plugin.interop.infrastructure.support;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minecraft 服务器状态探测（原版 handshake+status 协议，无第三方依赖），
 * 供启动器友站页展示服务器图标/在线人数（与 minecraft-server 服务器页同款体验）。
 *
 * <p>SWR 缓存：新鲜值直接回；过期值回旧值并后台单飞刷新；冷地址同步并行探测
 * （总超时封顶，友站地址个位数，页面加载最多多等一次探测时长）。单站失败记为
 * offline，不拖垮整页。仅直连 host:port，不做 SRV 解析（友站发布地址原样连）。</p>
 */
public class ServerStatusProbe {

    /** 单条探测结果；offline 时除 fetchedAt 外均为空值语义。 */
    public record Status(boolean online, String motd, long onlinePlayers, long maxPlayers,
                         String favicon, String versionName, long pingMs, long fetchedAt) {

        public static Status offline(long fetchedAt) {
            return new Status(false, null, 0, 0, null, null, 0, fetchedAt);
        }
    }

    private record Entry(Status status, long at) {
    }

    /** 新鲜窗口：模块页 60s 自动刷新 + 适配器数据源 60s 缓存，45s 窗口命中两次刷新。 */
    private static final long CACHE_TTL_MS = 45_000L;
    private static final int CONNECT_TIMEOUT_MS = 2_500;
    private static final int READ_TIMEOUT_MS = 3_000;
    /** 冷地址同步探测的整批墙钟上限。 */
    private static final long COLD_BATCH_TIMEOUT_MS = 5_000L;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean refreshing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.ExecutorService executor;

    public ServerStatusProbe() {
        AtomicInteger seq = new AtomicInteger();
        executor = new ThreadPoolExecutor(0, 6, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "mc-interop-status-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 批量取状态：新鲜的直接回；过期的回旧值并触发后台刷新；完全没有的同步并行
     * 探测（墙钟封顶，超时未归的地址回 null，页面按未知渲染）。返回的 map 不含
     * null 值键——调用方以 containsKey 区分「未知」与「离线」。
     */
    public Map<String, Status> snapshot(Collection<String> addresses) {
        Map<String, Status> result = new LinkedHashMap<>();
        List<String> cold = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String address : addresses) {
            if (address == null || address.isBlank()) {
                continue;
            }
            Entry entry = cache.get(address);
            if (entry == null) {
                cold.add(address);
                continue;
            }
            result.put(address, entry.status());
            if (now - entry.at() > CACHE_TTL_MS) {
                kickRefresh(address);
            }
        }
        if (!cold.isEmpty()) {
            probeAll(cold).forEach(result::put);
        }
        return result;
    }

    /** 后台单飞刷新：进行中直接返回，避免模块页 60s 定时刷新叠加外呼。 */
    private void kickRefresh(String address) {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    refreshOne(address);
                } catch (Exception ignored) {
                    // 后台刷新失败保持旧值，下个窗口再触发
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            refreshing.set(false);
        }
    }

    private void refreshOne(String address) {
        cache.put(address, new Entry(probeOnce(address), System.currentTimeMillis()));
    }

    private Map<String, Status> probeAll(List<String> addresses) {
        Map<String, Status> result = new LinkedHashMap<>();
        try {
            List<java.util.concurrent.Callable<Status>> tasks = new ArrayList<>();
            for (String address : addresses) {
                tasks.add(() -> probeOnce(address));
            }
            List<java.util.concurrent.Future<Status>> futures =
                    executor.invokeAll(tasks, COLD_BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            for (int index = 0; index < futures.size(); index++) {
                String address = addresses.get(index);
                Status status;
                try {
                    status = futures.get(index).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    status = null;
                }
                if (status != null) {
                    cache.put(address, new Entry(status, System.currentTimeMillis()));
                    result.put(address, status);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    /** 解析 "host[:port]"；裸 host 用 25565。非法地址回 null。 */
    static InetSocketAddress parseAddress(String address) {
        String trimmed = address.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String host = trimmed;
        int port = 25565;
        int colon = trimmed.lastIndexOf(':');
        if (colon > 0 && colon < trimmed.length() - 1
                && !trimmed.contains("]")) {
            host = trimmed.substring(0, colon);
            try {
                port = Integer.parseInt(trimmed.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (port < 1 || port > 65535) {
                return null;
            }
        }
        return new InetSocketAddress(host, port);
    }

    /** 同步探测一次；任何失败归为 offline（带 fetchedAt），不抛出。 */
    public Status probeOnce(String address) {
        long start = System.currentTimeMillis();
        InetSocketAddress endpoint = parseAddress(address);
        if (endpoint == null) {
            return Status.offline(System.currentTimeMillis());
        }
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            byte[] hostBytes = endpoint.getHostName().getBytes(StandardCharsets.UTF_8);
            // handshake：id=0，协议版本 -1，主机、端口、 nextState=1(status)
            int payload = varIntSize(-1) + varIntSize(hostBytes.length) + hostBytes.length
                    + 2 + 1;
            writeVarint(out, payload + 1);
            writeVarint(out, 0);
            writeVarint(out, -1);
            writeVarint(out, hostBytes.length);
            out.write(hostBytes);
            out.writeShort(endpoint.getPort());
            writeVarint(out, 1);
            // status request：id=0，无字段
            writeVarint(out, 1);
            writeVarint(out, 0);
            out.flush();

            int total = readVarint(in);
            int packetId = readVarint(in);
            if (packetId != 0 || total <= 0) {
                return Status.offline(System.currentTimeMillis());
            }
            int jsonLength = readVarint(in);
            byte[] jsonBytes = new byte[jsonLength];
            in.readFully(jsonBytes);
            long pingMs = System.currentTimeMillis() - start;
            return parseStatus(new String(jsonBytes, StandardCharsets.UTF_8), pingMs);
        } catch (Exception e) {
            return Status.offline(System.currentTimeMillis());
        }
    }

    /** 从 status JSON 提取展示字段；favicon 归一为 data URI。 */
    static Status parseStatus(String json, long pingMs) {
        long now = System.currentTimeMillis();
        Map<String, Object> root;
        try {
            root = JsonSupport.getMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Status.offline(now);
        }
        String motd = textOf(root.get("description"));
        String favicon = faviconOf(root.get("favicon"));
        String versionName = root.get("version") instanceof Map<?, ?> version
                ? textOf(version.get("name")) : null;
        long online = 0;
        long max = 0;
        if (root.get("players") instanceof Map<?, ?> players) {
            online = numberOf(players.get("online"));
            max = numberOf(players.get("max"));
        }
        return new Status(true, motd, online, max, favicon, versionName, pingMs, now);
    }

    /** description 可能是纯文本、{text} 或嵌套组件树；取可见文本拼接。 */
    private static String textOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.isBlank() ? null : text;
        }
        if (value instanceof Map<?, ?> map) {
            String own = map.get("text") instanceof String text ? text : "";
            StringBuilder builder = new StringBuilder(own);
            if (map.get("extra") instanceof List<?> extra) {
                for (Object child : extra) {
                    String part = textOf(child);
                    if (part != null) {
                        builder.append(part);
                    }
                }
            }
            String joined = builder.toString();
            return joined.isBlank() ? null : joined;
        }
        return null;
    }

    private static String faviconOf(Object value) {
        if (!(value instanceof String favicon) || favicon.isBlank()) {
            return null;
        }
        return favicon.startsWith("data:") ? favicon : "data:image/png;base64," + favicon;
    }

    private static long numberOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    private static void writeVarint(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarint(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int current = in.readUnsignedByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt 过长");
            }
        }
    }
}
