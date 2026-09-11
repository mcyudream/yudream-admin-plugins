package online.yudream.base.plugin.mcnews.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.mcnews.domain.NewsSource;
import org.junit.jupiter.api.Test;

class NewsFetchServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static NewsSource source(String type, List<String> keywords) {
        return new NewsSource("src", "测试源", type, "https://example.com/json", keywords, true, false, 0);
    }

    @Test
    void parsesMcnetArticleGrid() throws Exception {
        String json = """
                {"article_grid":[
                  {"default_tile":{"title":"Minecraft 26.3 Release Candidate 2","sub_header":"A Minecraft Java Release Candidate",
                    "image":{"imageURL":"/content/dam/a.jpg"}},"primary_category":"News","article_url":"/zh-hans/article/mc-26-3-rc2"},
                  {"default_tile":{"title":"无图文章","sub_header":"","image":{"imageURL":""}},
                    "primary_category":"Marketplace","article_url":"/zh-hans/article/no-image"}
                ]}
                """;
        JsonNode root = mapper.readTree(json);
        var method = NewsFetchService.class.getDeclaredMethod("parseMcnet", NewsSource.class, JsonNode.class, long.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<NewsArticle> items = (List<NewsArticle>) method.invoke(new NewsFetchService(HttpClientStub.create(), mapper),
                source(NewsSource.TYPE_MCNET, List.of()), root, 1000L);

        assertEquals(2, items.size());
        NewsArticle first = items.get(0);
        assertEquals("mcnet:article/mc-26-3-rc2", first.id());
        assertEquals("https://www.minecraft.net/zh-hans/article/mc-26-3-rc2", first.url());
        assertEquals("https://www.minecraft.net/content/dam/a.jpg", first.imageUrl());
        assertEquals("News", first.category());
        assertEquals("A Minecraft Java Release Candidate", first.summary());
        assertEquals(1000L, first.publishedAt());
        assertTrue(items.get(1).imageUrl().isEmpty());
    }

    @Test
    void parsesZendeskArticles() throws Exception {
        String json = """
                {"articles":[
                  {"id":48740748263565,"title":"Minecraft Beta & Preview - 26.60.22/23",
                   "created_at":"2026-09-08T14:26:58Z","html_url":"https://feedback.minecraft.net/hc/en-us/articles/1"}
                ]}
                """;
        JsonNode root = mapper.readTree(json);
        var method = NewsFetchService.class.getDeclaredMethod("parseZendesk", NewsSource.class, JsonNode.class, long.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<NewsArticle> items = (List<NewsArticle>) method.invoke(new NewsFetchService(HttpClientStub.create(), mapper),
                source(NewsSource.TYPE_ZENDESK, List.of()), root, 9999L);

        assertEquals(1, items.size());
        NewsArticle article = items.get(0);
        assertEquals("zendesk:48740748263565", article.id());
        assertTrue(article.publishedAt() > 0);
        assertEquals("https://feedback.minecraft.net/hc/en-us/articles/1", article.url());
    }

    @Test
    void keywordFilterMatchesTitleOrSummaryCaseInsensitive() {
        List<NewsArticle> items = List.of(
                article("Minecraft 26.3 Release Candidate 2", "A Minecraft Java Release Candidate"),
                article("LET'S PLAY: ETERNAL CAVES", "Marketplace 博客"),
                article("a minecraft java snapshot", ""));
        assertEquals(2, NewsFetchService.filterKeywords(items, List.of("A Minecraft Java")).size());
        assertEquals(3, NewsFetchService.filterKeywords(items, List.of()).size());
        assertEquals(1, NewsFetchService.filterKeywords(items, List.of("marketplace")).size());
    }

    @Test
    void articleSlugStripsLanguagePrefix() {
        assertEquals("article/mc-26-3", NewsFetchService.articleSlug("/zh-hans/article/mc-26-3"));
        assertEquals("article/mc-26-3", NewsFetchService.articleSlug("/en-us/article/mc-26-3"));
        assertEquals("/custom/path", NewsFetchService.articleSlug("/custom/path"));
    }

    @Test
    void parseInstantHandlesIsoAndFallback() {
        assertEquals(123L, NewsFetchService.parseInstant("bogus", 123L));
        assertTrue(NewsFetchService.parseInstant("2026-09-08T14:26:58Z", 0L) > 0);
    }

    @Test
    void htmlToTextKeepsListBulletsAndEntities() {
        String html = "<p><strong>Posted:</strong> 8 September 2026</p>"
                + "<ul><li>Fixed <a href=\"#\">MC-309262</a> &amp; related crash</li>"
                + "<li>Added new blocks &lt;test&gt; it&#39;s</li></ul>"
                + "<script>alert(1)</script><h3>Technical Changes</h3>";
        String text = NewsFetchService.htmlToText(html);
        assertTrue(text.contains("Posted: 8 September 2026"));
        assertTrue(text.contains("• Fixed MC-309262 & related crash"));
        assertTrue(text.contains("• Added new blocks <test> it's"));
        assertTrue(text.contains("Technical Changes"));
        assertTrue(!text.contains("alert"));
        assertTrue(!text.contains("<a") && !text.contains("<li"));
    }

    @Test
    void htmlToTextCollapsesBlankLines() {
        String text = NewsFetchService.htmlToText("<p>a</p>\n<p></p>\n<p>b</p>");
        assertEquals("a\nb", text);
    }

    @Test
    void unescapeHandlesNumericEntities() {
        assertEquals("中A", NewsFetchService.unescapeEntities("&#20013;&#65;"));
        assertEquals("keep &unknown; as is", NewsFetchService.unescapeEntities("keep &unknown; as is"));
    }

    private NewsArticle article(String title, String summary) {
        return new NewsArticle("id-" + title, "src", "源", title, summary, "", "", "", "", 0, 0, 0, "");
    }

    /** 仅复用 HttpClient 构造；解析测试不发真实请求。 */
    static final class HttpClientStub {
        static java.net.http.HttpClient create() {
            return java.net.http.HttpClient.newBuilder().build();
        }
    }
}
