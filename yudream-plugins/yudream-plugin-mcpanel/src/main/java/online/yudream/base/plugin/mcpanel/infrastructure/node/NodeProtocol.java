package online.yudream.base.plugin.mcpanel.infrastructure.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 协议 v1 信封（唯一 source：docs/mc-panel-protocol-v1.md §4）。
 * - 单帧序列化后 ≤ 256KB，超限属 proto.badFrame，接收侧以 1009 关闭；
 * - req id 为 ULID 字符串；res 回显；evt 无 id、携带每连接每 topic 单调 seq；
 * - 未知字段忽略；本类是面板侧唯一编解码点。
 */
public final class NodeProtocol {

    public static final int PROTOCOL_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String PANEL_VERSION = "0.2.0";

    public static final String T_REQ = "req";
    public static final String T_RES = "res";
    public static final String T_ERR = "err";
    public static final String T_EVT = "evt";

    public static final String M_NODE_HELLO = "node.hello";
    public static final String T_NODE_STATS = "node.stats";

    /** 协议 §3：面板 15s 未收到任何帧 → offline（仅 ONLINE 态适用）。 */
    public static final long OFFLINE_AFTER_MS = 15_000L;
    /** 协议 §4：req 默认超时 30s。 */
    public static final long REQ_TIMEOUT_MS = 30_000L;
    /** 协议 §4：单帧字节上限。 */
    public static final int MAX_FRAME_BYTES = 256 * 1024;
    /** transport 内部：每连接出站写队列容量（满则立即拒绝新调用，不静默丢帧）。 */
    public static final int OUTBOUND_QUEUE_CAPACITY = 64;
    /** transport 内部：单帧写出超时（sendText future 迟迟不完成视为连接坏死）。 */
    public static final long OUTBOUND_WRITE_TIMEOUT_MS = 10_000L;

    /**
     * err 帧 → 业务异常：载荷 {code,message,retryable}，code/message 原样透传，
     * 让上层（call 的映射与人话提示）拿到的就是节点给出的可读消息。
     */
    public static online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException parseErrFrame(
            Envelope envelope) {
        String code = "internal.error";
        String message = "";
        if (envelope != null && envelope.p() instanceof Map<?, ?> payload) {
            Object codeObj = payload.get("code");
            Object msgObj = payload.get("message");
            if (codeObj != null) {
                code = String.valueOf(codeObj);
            }
            if (msgObj != null) {
                message = String.valueOf(msgObj);
            }
        }
        if (message.isBlank()) {
            message = code;
        }
        return new online.yudream.base.plugin.mcpanel.infrastructure.node.NodeCallException(code, message);
    }
    /** 协议 §3：重连退避 1s → 30s 封顶 + 抖动。 */
    public static final long RECONNECT_BACKOFF_BASE_MS = 1_000L;
    public static final long RECONNECT_BACKOFF_MAX_MS = 30_000L;
    public static final int CLOSE_TOO_BIG = 1009;
    public static final int CLOSE_POLICY_VIOLATION = 1008;
    public static final int CLOSE_NORMAL = 1000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ULID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private NodeProtocol() {
    }

    /**
     * 信封。evt 不带 id、可带 seq；res 由发送方回显 req id。
     */
    public record Envelope(int v, String t, String m, String id, Long seq, long ts, Map<String, Object> p) {

        public static Envelope request(String method, Map<String, Object> payload) {
            return new Envelope(PROTOCOL_VERSION, T_REQ, method, newUlid(), null,
                    System.currentTimeMillis(), payload == null ? Map.of() : payload);
        }

        public static Envelope response(Envelope request, Map<String, Object> payload) {
            return new Envelope(PROTOCOL_VERSION, T_RES, request.m(), request.id(), null,
                    System.currentTimeMillis(), payload == null ? Map.of() : payload);
        }

        public boolean valid() {
            return v == PROTOCOL_VERSION && t != null && !t.isBlank() && m != null && !m.isBlank();
        }
    }

    public static String encode(Envelope envelope) {
        try {
            ObjectMapper mapper = McpanelJson.mapper();
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("v", envelope.v());
            if (envelope.id() != null) {
                frame.put("id", envelope.id());
            }
            frame.put("t", envelope.t());
            frame.put("m", envelope.m());
            frame.put("ts", envelope.ts());
            if (envelope.seq() != null) {
                frame.put("seq", envelope.seq());
            }
            frame.put("p", envelope.p() == null ? Map.of() : envelope.p());
            return mapper.writeValueAsString(frame);
        } catch (Exception error) {
            throw new IllegalStateException("协议帧序列化失败", error);
        }
    }

    /** 容错解码：malformed / 非对象帧返回 empty（调用方计 proto.badFrame）。 */
    public static Optional<Envelope> decode(String frame) {
        if (frame == null || frame.isBlank()) {
            return Optional.empty();
        }
        try {
            ObjectMapper mapper = McpanelJson.mapper();
            var tree = mapper.readTree(frame);
            if (!tree.isObject()) {
                return Optional.empty();
            }
            int v = tree.path("v").asInt(0);
            String t = tree.path("t").asText(null);
            String m = tree.path("m").asText(null);
            String id = tree.path("id").isMissingNode() || tree.path("id").isNull() ? null : tree.path("id").asText();
            Long seq = tree.path("seq").isMissingNode() || tree.path("seq").isNull() || !tree.path("seq").canConvertToLong()
                    ? null : tree.path("seq").asLong();
            long ts = tree.path("ts").asLong(0L);
            Map<String, Object> p = mapper.convertValue(tree.path("p"),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Envelope envelope = new Envelope(v, t, m, id, seq, ts, p == null ? Map.of() : p);
            return envelope.valid() ? Optional.of(envelope) : Optional.empty();
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    public static Optional<Map<String, Object>> helloPayload(List<String> caps, String dockerVersion,
                                                             String agentVersion, String hostname, String nodeId,
                                                             String sessionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", nodeId);
        payload.put("sessionId", sessionId);
        payload.put("hostname", hostname);
        payload.put("agentVersion", agentVersion);
        payload.put("caps", caps == null ? List.of() : caps);
        if (dockerVersion != null && !dockerVersion.isBlank()) {
            payload.put("dockerVersion", dockerVersion);
        }
        return Optional.of(payload);
    }

    /** 标准 ULID：48bit 毫秒时间戳（10 字符）+ 80bit 随机数（16 字符）= 26 字符 Crockford Base32。 */
    public static String newUlid() {
        long time = System.currentTimeMillis();
        byte[] entropy = new byte[10];
        RANDOM.nextBytes(entropy);
        char[] chars = new char[26];
        for (int i = 0; i < 10; i++) {
            int shift = 45 - i * 5;
            chars[i] = ULID_ALPHABET[(int) ((time >>> shift) & 0x1f)];
        }
        int bitOffset = 0;
        for (int i = 10; i < 26; i++) {
            int value = 0;
            for (int bit = 0; bit < 5; bit++) {
                int byteIndex = bitOffset >> 3;
                int bitIndex = bitOffset & 7;
                value = (value << 1) | ((entropy[byteIndex] >> (7 - bitIndex)) & 1);
                bitOffset++;
            }
            chars[i] = ULID_ALPHABET[value];
        }
        return new String(chars);
    }
}
