package online.yudream.plugin.webcard.infrastructure;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static online.yudream.plugin.webcard.domain.WebCardModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentParserTest {
    private final ContentParser parser = new ContentParser();

    @Test void extractsRequiredHtmlFieldsAndAbsoluteUrls() {
        ParseRules rules = new ParseRules("site", SourceType.HTML, List.of(
                new FieldRule("title", "article h1", "text", "TEXT", true),
                new FieldRule("image", "article img", "src", "URL", false)
        ), "article a", "href", null, "url", "url");
        var fetched = fetched("<article><h1>Title</h1><img src='/cover.png'><a href='/next'>Next</a></article>", "text/html");
        var fields = parser.detail(rules, fetched);
        assertEquals("Title", fields.get("title"));
        assertEquals("https://news.example.com/cover.png", fields.get("image"));
        assertEquals(List.of("https://news.example.com/next"), parser.discover(SourceType.HTML, rules, fetched));
    }

    @Test void rejectsXmlWithDoctype() {
        byte[] xml = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss><item><link>&xxe;</link></item></rss>".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> parser.discover(SourceType.RSS,
                new ParseRules("site", SourceType.HTML, List.of(), null, null, null, null, null),
                new SecureWebFetcher.Fetched(URI.create("https://news.example.com/feed"), 200, "application/xml", xml)));
    }

    @Test void supplementsUsefulOpenGraphResources() {
        ParseRules rules = new ParseRules("site", SourceType.HTML, List.of(), null, null, null, "url", "url", "/class/{id}.html");
        var fetched = fetched("<html><head><title>Useful title</title><meta name='description' content='Useful summary'><meta property='og:image' content='/cover.jpg'></head></html>", "text/html");
        var fields = parser.detail(rules, fetched);
        assertEquals("Useful title", fields.get("title"));
        assertEquals("Useful summary", fields.get("summary"));
        assertEquals("https://news.example.com/cover.jpg", fields.get("image"));
        assertEquals("news.example.com", fields.get("source"));
    }

    @Test void extractsNestedKeyValuesListsAuthorsTagsAndLinks() {
        ParseRules rules = new ParseRules("site", SourceType.HTML, List.of(
                new FieldRule("基础信息", "ul.col-lg-12 > li.col-lg-4", "text", "KEY_VALUE_LIST", true),
                new FieldRule("支持版本", "li.mcver a", "text", "TEXT_LIST", true),
                new FieldRule("作者", "li.author .member .name a", "text", "TEXT_LIST", true),
                new FieldRule("标签", "li.tag a", "text", "TEXT_LIST", true),
                new FieldRule("相关链接", ".common-link-icon-frame li", "href", "LINK_LIST", true),
                new FieldRule("image", ".cover", "src", "URL", true)
        ), null, null, null, "url", "url", "/class/{id}.html");
        var fetched = new SecureWebFetcher.Fetched(URI.create("https://www.mcmod.cn/class/17142.html"), 200, "text/html", """
                <html><body>
                <img class="cover" alt="更多进度" src="//i.mcmod.cn/class/cover/example.jpg">
                <ul class="col-lg-12">
                  <li class="col-lg-4">支持平台: <a>JAVA版 (JAVA Edition)</a></li>
                  <li class="col-lg-4">运作方式: <a>数据包</a></li>
                  <li class="col-lg-4">运行环境: 客户端可选, 服务端需装</li>
                  <li class="col-lg-4">编辑次数: 8次</li>
                  <li class="col-lg-12 tag">模组标签: <ul><li><a href="/s?key=进度">进度</a></li></ul></li>
                  <div class="common-link-frame"><ul class="common-link-icon-frame">
                    <li><a data-original-title="Modrinth" href="//link.mcmod.cn/target/modrinth"></a><span>Modrinth</span></li>
                    <li><a data-original-title="GitHub" href="//link.mcmod.cn/target/github"></a><span>GitHub</span></li>
                  </ul></div>
                  <li class="col-lg-12 mcver"><ul><li><a>1.21.11</a></li><li><a>1.21.10</a></li></ul></li>
                  <li class="col-lg-12 author"><span class="member"><span class="name"><a href="/author/33725.html">MC的小方块</a></span></span></li>
                </ul>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> fields = parser.detail(rules, fetched);
        assertEquals(Map.of(
                "支持平台", "JAVA版 (JAVA Edition)",
                "运作方式", "数据包",
                "运行环境", "客户端可选, 服务端需装",
                "编辑次数", "8次"
        ), fields.get("基础信息"));
        assertEquals(List.of("1.21.11", "1.21.10"), fields.get("支持版本"));
        assertEquals(List.of("MC的小方块"), fields.get("作者"));
        assertEquals(List.of("进度"), fields.get("标签"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> links = (List<Map<String, String>>) fields.get("相关链接");
        assertEquals(List.of("Modrinth", "GitHub"), links.stream().map(value -> value.get("label")).toList());
        assertEquals("https://i.mcmod.cn/class/cover/example.jpg", fields.get("image"));

        Map<String, Object> inspection = parser.inspect(fetched);
        assertTrue(((List<?>) inspection.get("assetHosts")).contains("i.mcmod.cn"));
        assertTrue(((List<?>) inspection.get("candidates")).stream().map(String::valueOf)
                .anyMatch(value -> value.contains("KEY_VALUE_LIST")));
    }

    private SecureWebFetcher.Fetched fetched(String value, String contentType) {
        return new SecureWebFetcher.Fetched(URI.create("https://news.example.com/article"), 200, contentType, value.getBytes(StandardCharsets.UTF_8));
    }
}
