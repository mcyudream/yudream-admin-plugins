package online.yudream.base.plugin.cas.infrastructure.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class HttpClientSupport {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_BODY_BYTES = 1_048_576;

    private HttpClientSupport() {
    }

    public static HttpResponse get(String url, Map<String, String> headers) {
        return exchange("GET", url, headers, null, null);
    }

    public static HttpResponse postForm(String url, Map<String, String> headers, String formBody) {
        return exchange("POST", url, headers, "application/x-www-form-urlencoded; charset=UTF-8", formBody);
    }

    public static HttpResponse postJson(String url, Map<String, String> headers, String jsonBody) {
        return exchange("POST", url, headers, "application/json; charset=UTF-8", jsonBody);
    }

    public static HttpResponse exchange(String method, String url, Map<String, String> headers, String contentType, String body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("User-Agent", "YuDream-cas/1.0");
            if (headers != null) {
                headers.forEach(connection::setRequestProperty);
            }
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", contentType);
                }
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = readLimited(stream);
            return new HttpResponse(status, responseBody);
        } catch (IOException e) {
            throw new IllegalStateException("请求认证端点失败：" + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new IOException("认证端点响应超过 1MB");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    public record HttpResponse(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }
}
