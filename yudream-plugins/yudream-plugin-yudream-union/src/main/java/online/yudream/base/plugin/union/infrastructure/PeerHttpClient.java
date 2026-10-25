package online.yudream.base.plugin.union.infrastructure;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 站间出站 HTTP 客户端：仅公开可验证的 HTTPS、SSRF 私网/环回拦截、实际响应连接的
 * TLS 叶子证书 pin 校验。禁止跳过 JVM 的标准 CA/hostname 校验；TOFU 仅用于在已验证 TLS
 * 基础上固定后续证书指纹，不支持自签名证书降级。
 */
public class PeerHttpClient {

    public record PeerResponse(String body, String tlsCertSha256) {
    }

    /** 带 HTTP 状态的站间响应：POST 用于机器面调用，非 2xx 由调用方按对端错误体透传。 */
    public record PeerJsonResponse(int status, String body, String tlsCertSha256) {
    }

    public static final class PeerHttpException extends RuntimeException {
        public PeerHttpException(String message) {
            super(message);
        }
    }

    private final HttpClient client;

    public PeerHttpClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * GET 站间资源；若存在 pin，则从**实际 HTTP 响应的 TLS 连接**取得叶子证书比对。
     * 非 2xx、重定向、无 TLS session、指纹不符全部 fail-closed。
     */
    public PeerResponse get(String url, Optional<String> pinnedCertSha256) {
        URI uri = validate(url);
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(uri)
                            .timeout(Duration.ofSeconds(3))
                            .header("User-Agent", "yudream-union/1")
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new PeerHttpException("对端响应状态 " + response.statusCode());
            }
            String leafSha256 = response.sslSession()
                    .map(session -> {
                        try {
                            Certificate[] chain = session.getPeerCertificates();
                            return chain.length == 0 ? "" : certSha256(chain[0]);
                        } catch (Exception e) {
                            throw new PeerHttpException("无法读取对端 TLS 证书：" + e.getMessage());
                        }
                    })
                    .orElseThrow(() -> new PeerHttpException("站间 HTTPS 响应缺少 TLS 会话"));
            if (pinnedCertSha256.isPresent() && !pinnedCertSha256.get().equalsIgnoreCase(leafSha256)) {
                throw new PeerHttpException("对端 TLS 证书指纹与注册 pin 不一致");
            }
            return new PeerResponse(response.body(), leafSha256);
        } catch (PeerHttpException e) {
            throw e;
        } catch (IOException e) {
            throw new PeerHttpException("对端不可达：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeerHttpException("站间请求被中断");
        }
    }

    /**
     * POST 站间机器面（如 /peer/enroll 邀请码自注册）：与 GET 同一套 https/SSRF/TLS-pin
     * 防护；非 2xx 不抛错，由调用方解析对端错误体（{error, errorMessage}）透传业务语义。
     */
    public PeerJsonResponse post(String url, Object jsonBody, Optional<String> pinnedCertSha256) {
        URI uri = validate(url);
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(uri)
                            .timeout(Duration.ofSeconds(5))
                            .header("User-Agent", "yudream-union/1")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    jsonBody == null ? "{}" : String.valueOf(jsonBody), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String leafSha256 = response.sslSession()
                    .map(session -> {
                        try {
                            Certificate[] chain = session.getPeerCertificates();
                            return chain.length == 0 ? "" : certSha256(chain[0]);
                        } catch (Exception e) {
                            throw new PeerHttpException("无法读取对端 TLS 证书：" + e.getMessage());
                        }
                    })
                    .orElseThrow(() -> new PeerHttpException("站间 HTTPS 响应缺少 TLS 会话"));
            if (pinnedCertSha256.isPresent() && !pinnedCertSha256.get().equalsIgnoreCase(leafSha256)) {
                throw new PeerHttpException("对端 TLS 证书指纹与注册 pin 不一致");
            }
            return new PeerJsonResponse(response.statusCode(), response.body(), leafSha256);
        } catch (PeerHttpException e) {
            throw e;
        } catch (IOException e) {
            throw new PeerHttpException("对端不可达：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeerHttpException("站间请求被中断");
        }
    }

    /** SSRF 防护：https + 解析结果必须全部为公网单播地址。 */
    private static URI validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new PeerHttpException("非法 URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new PeerHttpException("站间地址必须为无用户信息的 https URL");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (privateOrSpecial(address)) {
                    throw new PeerHttpException("对端地址解析到受限网络，已拒绝");
                }
            }
        } catch (IOException e) {
            throw new PeerHttpException("对端域名解析失败：" + e.getMessage());
        }
        return uri;
    }

    private static boolean privateOrSpecial(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            // 0/8、100.64/10（CGNAT）、127/8、169.254/16、198.18/15、224/4 及 RFC1918
            return first == 0 || first == 10 || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19))
                    || first >= 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            // fc00::/7 ULA；IPv4-mapped 地址也拒绝，由末尾 IPv4 分类兜底。
            if ((first & 0xfe) == 0xfc) {
                return true;
            }
            if (first == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0 && bytes[4] == 0
                    && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0 && bytes[8] == 0 && bytes[9] == 0
                    && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
                try {
                    return privateOrSpecial(InetAddress.getByAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]}));
                } catch (IOException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String certSha256(Certificate certificate) {
        try {
            return sha256Hex(certificate.getEncoded());
        } catch (java.security.cert.CertificateException e) {
            throw new PeerHttpException("TLS 证书编码失败：" + e.getMessage());
        }
    }
}
