package online.yudream.base.plugin.eduverify.infrastructure;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compatible, bounded adaptation of Ernket/edu-crawler. It reads school names
 * from edusrc and asks Bing for a public edu.cn domain candidate. Results are
 * candidates only; the application layer decides whether to persist them.
 */
public final class EduCrawlerClient {

    private static final URI EDUSRC_PAGE = URI.create("https://src.sjtu.edu.cn/rank/firm/?page=");
    private static final String BING_SEARCH = "https://cn.bing.com/search?q=";
    private static final Pattern DOMAIN = Pattern.compile("(?i)(?:https?://)?([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\\.edu\\.cn)");
    private static final int MAX_PAGES = 3;
    private static final int MAX_SCHOOLS = 50;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private final HttpClient http;

    public EduCrawlerClient(HttpClient http) {
        this.http = http;
    }

    public List<Candidate> crawl(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalArgumentException("请先在插件密钥存储中配置 edusrc Cookie");
        }
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int remaining = MAX_SCHOOLS;
        for (int page = 1; page <= MAX_PAGES && remaining > 0; page++) {
            List<String> schools = schools(fetch(URI.create(EDUSRC_PAGE + String.valueOf(page)), cookie));
            for (String school : schools) {
                if (remaining-- <= 0) {
                    break;
                }
                findDomain(school).ifPresent(domain -> candidates.putIfAbsent(domain,
                        new Candidate(domain, school)));
            }
        }
        return List.copyOf(candidates.values());
    }

    private Optional<String> findDomain(String school) {
        String query = URLEncoder.encode(school + " 官网 edu.cn", StandardCharsets.UTF_8);
        String html = fetch(URI.create(BING_SEARCH + query), null);
        Matcher matcher = DOMAIN.matcher(html);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String domain = matcher.group(1).toLowerCase(Locale.ROOT);
        return Optional.of(domain);
    }

    private List<String> schools(String html) {
        Document document = Jsoup.parse(html);
        return document.select("td.am-text-center a").eachText().stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String fetch(URI uri, String cookie) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "YuDream EduVerify domain importer/1.0")
                    .GET();
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie.trim());
            }
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("学校域名来源响应异常：HTTP " + response.statusCode());
            }
            if (response.body().length > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("学校域名来源响应过大");
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("学校域名来源读取失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("学校域名来源读取被中断", e);
        }
    }

    public record Candidate(String domain, String chineseName) {
    }
}
