package online.yudream.plugin.webcard.infrastructure;

import online.yudream.plugin.webcard.domain.WebCardModels.Site;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SecureWebFetcher {
    public record Fetched(URI finalUri, int status, String contentType, byte[] body) {
        public String text() {
            String charset = java.util.regex.Pattern.compile("charset=([^;\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(contentType == null ? "" : contentType).results().map(m -> m.group(1)).findFirst().orElse("UTF-8");
            try { return new String(body, Charset.forName(charset)); } catch (Exception ignored) { return new String(body, StandardCharsets.UTF_8); }
        }
    }

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public Fetched fetch(Site site, String url, Map<String, String> headers) {
        return fetch(site, url, headers, false);
    }

    public Fetched fetchResource(Site site, String url, Map<String, String> headers) {
        return fetch(site, url, headers, true);
    }

    private Fetched fetch(Site site, String url, Map<String, String> headers, boolean allowResourceHost) {
        URI current = validate(URI.create(url), site, true, allowResourceHost);
        Map<String, String> activeHeaders = allowResourceHost
                ? resourceHeaders(site, current, headers)
                : headers == null ? Map.of() : headers;
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(current).GET().timeout(Duration.ofSeconds(20)).header("User-Agent", "YuDream-WebCard/1.0").header("Accept", "text/html,application/json,application/rss+xml,application/xml,text/xml;q=0.9,*/*;q=0.5");
            activeHeaders.forEach(request::header);
            HttpResponse<byte[]> response;
            try { response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()); }
            catch (Exception e) { throw new IllegalArgumentException("抓取失败：" + safeMessage(e), e); }
            if (response.body().length > MAX_BYTES) throw new IllegalArgumentException("响应超过 5 MB 限制");
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                String location = response.headers().firstValue("location").orElseThrow(() -> new IllegalArgumentException("重定向缺少 Location"));
                URI next = validate(current.resolve(location), site, false, allowResourceHost);
                if (!sameHost(current, next)) activeHeaders = Map.of();
                current = next;
                continue;
            }
            String contentType = response.headers().firstValue("content-type").orElse("application/octet-stream");
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalArgumentException("网站返回 HTTP " + response.statusCode());
            if (!allowedContentType(contentType)) throw new IllegalArgumentException("不支持的响应类型：" + contentType);
            return new Fetched(current, response.statusCode(), contentType, response.body());
        }
        throw new IllegalArgumentException("重定向次数过多");
    }

    static Map<String, String> resourceHeaders(Site site, URI resource, Map<String, String> headers) {
        return site.matches(resource.getHost()) && headers != null ? headers : Map.of();
    }

    private URI validate(URI uri, Site site, boolean initial, boolean allowResourceHost) {
        if (uri == null || uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getUserInfo() != null) throw new IllegalArgumentException("仅支持无用户信息的 HTTP(S) URL");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = initial ? allowsInitialHost(site, host, allowResourceHost) : site.allowsRedirect(host);
        if (!allowed) throw new IllegalArgumentException("URL 域名未被站点允许");
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) if (blocked(address)) throw new IllegalArgumentException("禁止访问内网或本地地址");
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("域名解析失败", e); }
        return uri.normalize();
    }
    static boolean allowsInitialHost(Site site, String host, boolean allowResourceHost) {
        return allowResourceHost ? site.allowsRedirect(host) : site.matches(host);
    }
    private boolean blocked(InetAddress value) {
        byte[] bytes = value.getAddress();
        if (value.isAnyLocalAddress() || value.isLoopbackAddress() || value.isLinkLocalAddress() || value.isSiteLocalAddress() || value.isMulticastAddress()) return true;
        if (bytes.length == 4) return (bytes[0] & 255) == 0 || (bytes[0] & 255) >= 224 || ((bytes[0] & 255) == 100 && (bytes[1] & 0xC0) == 64) || ((bytes[0] & 255) == 169 && (bytes[1] & 255) == 254);
        return false;
    }
    private boolean sameHost(URI left, URI right) { return left.getHost().equalsIgnoreCase(right.getHost()); }
    private boolean allowedContentType(String value) { String type = value.toLowerCase(Locale.ROOT); return List.of("text/html", "application/json", "text/json", "application/xml", "text/xml", "application/rss+xml", "application/atom+xml", "image/").stream().anyMatch(type::startsWith); }
    private String safeMessage(Exception e) { return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage(); }
}
