package online.yudream.base.plugin.mcpanel.infrastructure.service;

import com.fasterxml.jackson.databind.JsonNode;
import online.yudream.base.plugin.mcpanel.infrastructure.support.McpanelJson;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * MC Java 版 server list ping（协议实现，无外部依赖）：
 * handshake(nextState=1) → status request → 读 JSON 状态帧 → ping/pong 测延迟。
 * 只做一次短连接，任何 IO/协议异常都以 reachable=false 收敛，绝不抛出——
 * 端口未放行、基岩版、非 MC 服务都只是「探测不到」。
 */
public final class ServerListPing {

    private ServerListPing() {
    }

    public record Sample(String name, String id) {
    }

    public record Result(boolean reachable, String version, String motd,
                         int online, int max, List<Sample> sample,
                         Long latencyMs, String error) {

        public static Result unreachable(String error) {
            return new Result(false, "", "", 0, 0, List.of(), null, error);
        }
    }

    public static Result ping(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // handshake：protocol=-1（纯状态查询），nextState=1（status）
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(handshake);
            writeVarInt(payload, -1);
            writeString(payload, host);
            payload.writeShort(port);
            writeVarInt(payload, 1);
            writeFrame(out, 0x00, handshake.toByteArray());
            writeFrame(out, 0x00, new byte[0]); // status request

            byte[] response = readFrame(in);
            DataInputStream body = new DataInputStream(new java.io.ByteArrayInputStream(response));
            int packetId = readVarInt(body);
            if (packetId != 0x00) {
                return Result.unreachable("协议响应异常（packetId=" + packetId + "）");
            }
            String json = readString(body);

            Long latencyMs = pingPong(out, in);

            return parse(json, latencyMs);
        } catch (SocketTimeoutException error) {
            return Result.unreachable("探测超时");
        } catch (IOException error) {
            return Result.unreachable(error.getClass().getSimpleName());
        } catch (RuntimeException error) {
            return Result.unreachable("响应解析失败");
        }
    }

    /** ping/pong 测延迟；失败只丢延迟值，不影响状态结果。 */
    private static Long pingPong(DataOutputStream out, DataInputStream in) {
        try {
            ByteArrayOutputStream pong = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(pong);
            payload.writeLong(System.currentTimeMillis());
            writeFrame(out, 0x01, pong.toByteArray());
            byte[] response = readFrame(in);
            DataInputStream body = new DataInputStream(new java.io.ByteArrayInputStream(response));
            int packetId = readVarInt(body);
            if (packetId != 0x01) {
                return null;
            }
            long sent = body.readLong();
            return System.currentTimeMillis() - sent;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static Result parse(String json, Long latencyMs) {
        try {
            JsonNode root = McpanelJson.mapper().readTree(json);
            JsonNode players = root.path("players");
            List<Sample> sample = new ArrayList<>();
            for (JsonNode item : players.path("sample")) {
                sample.add(new Sample(item.path("name").asText(""), item.path("id").asText("")));
            }
            String motd = root.path("description").isTextual()
                    ? root.path("description").asText("")
                    : root.path("description").path("text").asText("");
            return new Result(true,
                    root.path("version").path("name").asText(""),
                    motd,
                    players.path("online").asInt(0),
                    players.path("max").asInt(0),
                    List.copyOf(sample),
                    latencyMs,
                    null);
        } catch (IOException | RuntimeException error) {
            return Result.unreachable("状态 JSON 解析失败");
        }
    }

    // ---------- MC 协议帧 ----------

    private static void writeFrame(DataOutputStream out, int packetId, byte[] body) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream head = new DataOutputStream(frame);
        writeVarInt(head, packetId);
        head.write(body);
        byte[] bytes = frame.toByteArray();
        writeVarInt(out, bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static byte[] readFrame(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0 || length > 1 << 20) {
            throw new IOException("非法帧长度：" + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > 1 << 20) {
            throw new IOException("非法字符串长度：" + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        // 负数按 32 位二补码展开（MC 协议语义）。
        int signed = value;
        while ((signed & ~0x7F) != 0) {
            out.writeByte((signed & 0x7F) | 0x80);
            signed >>>= 7;
        }
        out.writeByte(signed);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
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
