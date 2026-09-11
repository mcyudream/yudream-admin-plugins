package online.yudream.base.plugin.mcnews.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsSource;

/**
 * 新闻源抓取与解析：minecraft.net 文章列表 JSON 与 minecraftfeedback Zendesk 文章 API。
 * 列表接口均只读 title/摘要/链接/时间等必要字段；单源失败抛出异常由轮询层兜底跳过。
 */
public final class NewsFetchService {
    private static final String MCNET_ORIGIN = "https://www.minecraft.net";
    private static final Pattern MCNET_ARTICLE_PATH = Pattern.compile("^/[a-z]{2}(?:-[a-z]+)?(/article/.+)$");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_CONTENT_HTML = 200_000;

    private final HttpClient http;
    private final ObjectMapper mapper;

    public NewsFetchService(HttpClient http, ObjectMapper mapper) {
        this.http = Objects.requireNonNull(http);
        this.mapper = Objects.requireNonNull(mapper);
    }

    /** 拉取并解析一个源，返回源内序（新→旧）的候选条目；关键词在源级过滤。 */
    public List<NewsArticle> fetch(NewsSource source) {
        JsonNode root = getJson(source.url());
        long now = System.currentTimeMillis();
        List<NewsArticle> items = source.mcnet() ? parseMcnet(source, root, now) : parseZendesk(source, root, now);
        return filterKeywords(items, source.keywords());
    }

    /**
     * 抓取文章正文纯文本，供 AI 整合保留具体信息（修复项、变更点、版本号）。
     * zendesk 走帮助中心单篇文章 API 取 body；mcnet 抓文章页并截取 article-section 到 footer 之间的正文区。
     * 任何失败都返回空串（回退为仅标题/摘要整合），不抛出。
     */
    public String fetchArticleContent(NewsArticle article) {
        try {
            if (article.id().startsWith("zendesk:")) {
                return zendeskContent(article.id().substring("zendesk:".length()));
            }
            if (article.id().startsWith("mcnet:")) {
                return mcnetContent(article.url());
            }
            return "";
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private String zendeskContent(String articleId) throws IOException, InterruptedException {
        if (!articleId.matches("\\d+")) {
            return "";
        }
        JsonNode root = getJson("https://minecraftfeedback.zendesk.com/api/v2/help_center/en-us/articles/"
                + articleId + ".json");
        return htmlToText(root.path("article").path("body").asText(""));
    }

    private String mcnetContent(String articleUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(articleUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) YuDream-McNews")
                .header("Accept", "text/html")
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            return "";
        }
        String html = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        int start = html.indexOf("class=\"article-section");
        if (start < 0) {
            return "";
        }
        int end = html.indexOf("<footer", start);
        if (end < 0 || end - start > MAX_CONTENT_HTML) {
            end = Math.min(html.length(), start + MAX_CONTENT_HTML);
        }
        return htmlToText(html.substring(start, end));
    }

    /** HTML 转纯文本：块级标签转换行、列表项加 • 前缀、去标签、还原常用实体、压缩空白。 */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?i)</(p|div|li|h[1-6]|tr|table|ul|ol|blockquote|pre)>", "\n");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)<li[^>]*>", "\n• ");
        text = text.replaceAll("<[^>]+>", "");
        text = unescapeEntities(text);
        text = text.replaceAll("[ \\t\\r\\f\\u00a0]+", " ");
        text = text.replaceAll(" ?\n ?", "\n");
        text = text.replaceAll("\n{2,}", "\n");
        return text.trim();
    }

    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("&amp;", "&"), Map.entry("&lt;", "<"), Map.entry("&gt;", ">"),
            Map.entry("&quot;", "\""), Map.entry("&apos;", "'"), Map.entry("&nbsp;", " "),
            Map.entry("&copy;", "©"), Map.entry("&reg;", "®"), Map.entry("&hellip;", "…"),
            Map.entry("&mdash;", "—"), Map.entry("&rsquo;", "'"), Map.entry("&lsquo;", "'"),
            Map.entry("&ldquo;", "“"), Map.entry("&rdquo;", "”"));

    static String unescapeEntities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entry : NAMED_ENTITIES.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        result = Pattern.compile("&#(\\d+);").matcher(result)
                .replaceAll(match -> {
                    int code = Integer.parseInt(match.group(1));
                    return code > 0 && code < Character.MAX_VALUE ? String.valueOf((char) code) : "";
                });
        result = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(result)
                .replaceAll(match -> {
                    try {
                        int code = Integer.parseInt(match.group(1), 16);
                        return code > 0 && code < Character.MAX_VALUE ? String.valueOf((char) code) : "";
                    }
                    catch (NumberFormatException e) {
                        return "";
                    }
                });
        return result;
    }

    private List<NewsArticle> parseMcnet(NewsSource source, JsonNode root, long now) {
        List<NewsArticle> items = new ArrayList<>();
        for (JsonNode node : root.path("article_grid")) {
            JsonNode tile = node.path("default_tile");
            String articleUrl = node.path("article_url").asText("");
            String title = tile.path("title").asText("");
            if (articleUrl.isBlank() || title.isBlank()) {
                continue;
            }
            String slug = articleSlug(articleUrl);
            String imageUrl = tile.path("image").path("imageURL").asText("");
            items.add(new NewsArticle("mcnet:" + slug,
                    source.id(), source.name(), title,
                    tile.path("sub_header").asText(""),
                    "", node.path("primary_category").asText(""),
                    MCNET_ORIGIN + articleUrl,
                    imageUrl.startsWith("/") ? MCNET_ORIGIN + imageUrl : imageUrl,
                    now, now, 0, NewsArticle.STATE_PENDING));
        }
        return items;
    }

    private List<NewsArticle> parseZendesk(NewsSource source, JsonNode root, long now) {
        List<NewsArticle> items = new ArrayList<>();
        for (JsonNode node : root.path("articles")) {
            String id = node.path("id").asText("");
            String title = node.path("title").asText("");
            if (id.isBlank() || title.isBlank()) {
                continue;
            }
            items.add(new NewsArticle("zendesk:" + id,
                    source.id(), source.name(), title,
                    "", "", "",
                    node.path("html_url").asText(""),
                    "",
                    parseInstant(node.path("created_at").asText(""), now),
                    now, 0, NewsArticle.STATE_PENDING));
        }
        return items;
    }

    /** 关键词命中 title 或摘要任一即保留；关键词为空表示不过滤。 */
    static List<NewsArticle> filterKeywords(List<NewsArticle> items, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return items;
        }
        List<String> lowered = keywords.stream().map(item -> item.toLowerCase(Locale.ROOT)).toList();
        List<NewsArticle> result = new ArrayList<>();
        for (NewsArticle item : items) {
            String haystack = (item.title() + "\n" + item.summary()).toLowerCase(Locale.ROOT);
            for (String keyword : lowered) {
                if (haystack.contains(keyword)) {
                    result.add(item);
                    break;
                }
            }
        }
        return result;
    }

    /** 文章稳定 ID：去掉语言前缀，避免切换官网语言导致整源重新推送。 */
    static String articleSlug(String articleUrl) {
        Matcher matcher = MCNET_ARTICLE_PATH.matcher(articleUrl);
        return matcher.matches() ? matcher.group(1).substring(1) : articleUrl;
    }

    static long parseInstant(String iso, long fallback) {
        if (iso == null || iso.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        }
        catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private JsonNode getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) YuDream-McNews")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("新闻源返回 HTTP " + response.statusCode());
            }
            return mapper.readTree(response.body());
        }
        catch (IOException e) {
            throw new IllegalStateException("新闻源请求失败：" + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("新闻源请求被中断", e);
        }
    }
}
