package online.yudream.plugin.webcard.domain;

import org.junit.jupiter.api.Test;
import java.util.List;

import static online.yudream.plugin.webcard.domain.WebCardModels.*;
import static org.junit.jupiter.api.Assertions.*;

class WebCardModelsTest {
    @Test void matchesOnlyExactConfiguredHosts() {
        Site site = new Site("site", "Site", true, List.of("News.Example.com."), AccessMode.PUBLIC_HTTP,
                List.of(), null, SourceType.HTML, List.of("cdn.example.com"), null, 0, 0);
        assertTrue(site.matches("news.example.com"));
        assertFalse(site.matches("news.example.com.attacker.test"));
        assertTrue(site.allowsRedirect("cdn.example.com"));
    }

    @Test void defaultsInitialCrawlCountToThree() {
        CrawlJob job = new CrawlJob("job", "site", "https://news.example.com/feed", SourceType.RSS,
                true, 30, 0, 0, null, 0, false, 0, 0);
        assertEquals(3, job.initialItemCount());
    }
}
