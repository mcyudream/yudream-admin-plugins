package online.yudream.base.plugin.cas.infrastructure.bridge;

import com.sun.net.httpserver.HttpServer;
import online.yudream.base.plugin.cas.domain.aggregate.SsoSettings;
import online.yudream.base.plugin.cas.domain.enumerate.SsoProtocol;
import online.yudream.base.plugin.cas.domain.service.SsoProtocolClient;
import online.yudream.base.plugin.cas.infrastructure.support.JsonSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeProtocolClientTest {

    private static final String SECRET = "test-secret";
    private static final String CLIENT_ID = "swustmc";

    private final BridgeProtocolClient client = new BridgeProtocolClient();
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private SsoSettings bridgeSettings() {
        return new SsoSettings(
                true,
                SsoProtocol.BRIDGE,
                "统一身份认证",
                "i-ri:graduation-cap-line",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "", "", "", "", "", "", "", "",
                CLIENT_ID,
                true,
                "",
                "https://www.swustmc.cn/external-login/callback",
                false
        );
    }

    @Test
    void hmacSha256HexMatchesRfc4231Vector() {
        // RFC 4231 Test Case 2：key="Jefe"，data="what do ya want for nothing?"
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                BridgeProtocolClient.hmacSha256Hex("Jefe", "what do ya want for nothing?"));
    }

    @Test
    void authorizationUrlCarriesClientRedirectAndState() {
        SsoSettings settings = bridgeSettings();
        String raw = client.rawAuthorizationUrl(settings, "s1");
        assertTrue(raw.startsWith("http://127.0.0.1:" + server.getAddress().getPort() + "/sso/start?"), raw);
        assertTrue(raw.contains("client=" + CLIENT_ID), raw);
        assertTrue(raw.contains("redirect=https%3A%2F%2Fwww.swustmc.cn%2Fexternal-login%2Fcallback"), raw);
        assertTrue(raw.endsWith("state=s1"), raw);
        // 关闭预热时授权地址就是裸中转地址
        assertEquals(raw, client.authorizationUrl(settings, "s1"));
        // 开启预热时先跳本站预热页，只带 state
        String warmed = client.authorizationUrl(settings.withLoginWarmup(true), "s1");
        assertTrue(warmed.startsWith("/api/plugins/cas/public/warmup?state="), warmed);
    }

    @Test
    void exchangeSignsRequestAndVerifiesResponse() {
        SsoSettings settings = bridgeSettings();
        server.createContext("/sso/exchange", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            long ts = 1792769760129L;
            String responseSign = BridgeProtocolClient.hmacSha256Hex(SECRET, "7020240533|赵仕杰|" + ts);
            byte[] body = JsonSupport.write(Map.of(
                    "ok", true,
                    "no", "7020240533",
                    "name", "赵仕杰",
                    "college", "计算机科学与技术学院",
                    "className", "2024计算机科学与技术（研）",
                    "ts", ts,
                    "sign", responseSign
            )).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        SsoProtocolClient.ExternalIdentity identity = client.exchange(settings, "one-time-code", "s1", SECRET);

        assertEquals("7020240533", identity.socialUid());
        assertEquals("赵仕杰", identity.nickname());
        assertEquals("计算机科学与技术学院", identity.attributes().get("college"));
        assertEquals("2024计算机科学与技术（研）", identity.attributes().get("className"));

        // 请求体必须带 clientId/code/ts/sign，且 sign = HMAC(secret, clientId.code.ts)
        var node = JsonSupport.tree(lastBody.get());
        assertEquals(CLIENT_ID, node.path("clientId").asText());
        assertEquals("one-time-code", node.path("code").asText());
        String expected = BridgeProtocolClient.hmacSha256Hex(SECRET,
                CLIENT_ID + "." + "one-time-code" + "." + node.path("ts").asLong());
        assertEquals(expected, node.path("sign").asText());
    }

    @Test
    void exchangeRejectsTamperedResponseSign() {
        SsoSettings settings = bridgeSettings();
        server.createContext("/sso/exchange", exchange -> {
            byte[] body = JsonSupport.write(Map.of(
                    "ok", true,
                    "no", "7020240533",
                    "name", "赵仕杰",
                    "college", "",
                    "className", "",
                    "ts", 1792769760129L,
                    "sign", "0".repeat(64)
            )).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.exchange(settings, "code", "s1", SECRET));
        assertTrue(e.getMessage().contains("签名校验失败"), e.getMessage());
    }

    @Test
    void exchangeMapsKnownErrorStatuses() {
        SsoSettings settings = bridgeSettings();
        server.createContext("/sso/exchange", exchange -> {
            byte[] body = JsonSupport.write(Map.of("ok", false, "error", "CODE_INVALID_OR_USED"))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.exchange(settings, "used-code", "s1", SECRET));
        assertTrue(e.getMessage().contains("CODE_INVALID_OR_USED"), e.getMessage());
        assertTrue(e.getMessage().contains("重新发起"), e.getMessage());
    }

    @Test
    void exchangeRejectsMissingCodeOrSecret() {
        SsoSettings settings = bridgeSettings();
        assertThrows(IllegalArgumentException.class, () -> client.exchange(settings, "", "s1", SECRET));
        assertThrows(IllegalArgumentException.class, () -> client.exchange(settings, "code", "s1", " "));
    }

    @Test
    void probeChecksHealthClientsRegistration() {
        SsoSettings settings = bridgeSettings();
        server.createContext("/sso/health", exchange -> {
            byte[] body = JsonSupport.write(Map.of(
                    "ok", true, "enabled", true, "clients", List.of(CLIENT_ID), "store", "redis"
            )).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        SsoProtocolClient.ConnectivityResult result = client.probe(settings, SECRET);
        assertTrue(result.ok(), result.message());

        // 未登记 clientId 时探活必须失败
        SsoSettings unknown = new SsoSettings(
                true, SsoProtocol.BRIDGE, "统一身份认证", "i-ri:graduation-cap-line",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "", "", "", "", "", "", "", "",
                "other-client", true, "",
                "https://www.swustmc.cn/external-login/callback", false
        );
        assertFalse(client.probe(unknown, SECRET).ok());
    }
}
