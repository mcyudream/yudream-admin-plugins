package online.yudream.base.plugin.mcpanel.infrastructure.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ServerListPing 协议回归：本地假 MC 服务端（握手/状态/ping-pong 全流程），
 * 覆盖正常解析、玩家样例、延迟测量与连接拒绝降级。
 */
class ServerListPingTest {

    private static final String STATUS_JSON = """
            {
              "version": {"name": "1.21.9", "protocol": 773},
              "players": {"online": 2, "max": 20,
                "sample": [{"name": "Steve", "id": "id-1"}, {"name": "Alex", "id": "id-2"}]},
              "description": {"text": "YuDream Panel"}
            }
            """;

    @Test
    void parsesStatusPlayersAndLatency() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    serveStatusHandshake(socket);
                } catch (IOException ignored) {
                } finally {
                    done.countDown();
                }
            });
            serverThread.start();

            ServerListPing.Result result = ServerListPing.ping(
                    "127.0.0.1", server.getLocalPort(), 2000, 2000);

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(result.reachable());
            assertEquals("1.21.9", result.version());
            assertEquals("YuDream Panel", result.motd());
            assertEquals(2, result.online());
            assertEquals(20, result.max());
            assertEquals(2, result.sample().size());
            assertEquals("Steve", result.sample().get(0).name());
            assertEquals("id-2", result.sample().get(1).id());
            assertNotNull(result.latencyMs());
            assertTrue(result.latencyMs() >= 0);
            assertNull(result.error());
        }
    }

    @Test
    void unreachableOnConnectionRefused() {
        // 拿一个确定空闲的端口（先开再关），连接应被拒绝或超时降级。
        int freePort;
        try (ServerSocket server = new ServerSocket(0)) {
            freePort = server.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        ServerListPing.Result result = ServerListPing.ping("127.0.0.1", freePort, 300, 300);
        assertFalse(result.reachable());
        assertNotNull(result.error());
        assertEquals(0, result.online());
        assertTrue(result.sample().isEmpty());
        assertNull(result.latencyMs());
    }

    /** 假服务端：读 handshake + status request，回状态帧，再答一次 ping-pong。 */
    private void serveStatusHandshake(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        readFrame(in); // handshake
        readFrame(in); // status request

        byte[] json = STATUS_JSON.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(frame);
        writeVarInt(body, 0x00);
        writeVarInt(body, json.length);
        body.write(json);
        writeFrame(out, frame.toByteArray());

        byte[] ping = readFrame(in); // ping
        writeFrame(out, ping);       // pong 原样返回
    }

    private void writeFrame(DataOutputStream out, byte[] bytes) throws IOException {
        writeVarInt(out, bytes.length);
        out.write(bytes);
        out.flush();
    }

    private byte[] readFrame(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private int readVarInt(DataInputStream in) throws IOException {
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
