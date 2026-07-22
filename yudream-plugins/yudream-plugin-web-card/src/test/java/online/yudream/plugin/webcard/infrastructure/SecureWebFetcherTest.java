package online.yudream.plugin.webcard.infrastructure;

import online.yudream.plugin.webcard.domain.WebCardModels.AccessMode;
import online.yudream.plugin.webcard.domain.WebCardModels.Site;
import online.yudream.plugin.webcard.domain.WebCardModels.SourceType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecureWebFetcherTest {

    @Test
    void resourceHostNeverReceivesPrimarySiteHeaders() {
        Site site = new Site("site", "MC百科", true, List.of("www.mcmod.cn"),
                AccessMode.CUSTOM_HEADERS, List.of("Cookie"), "secret", SourceType.HTML,
                List.of("i.mcmod.cn"), null, 0, 0);
        Map<String, String> headers = Map.of("Cookie", "session=secret");

        assertEquals(headers, SecureWebFetcher.resourceHeaders(
                site, URI.create("https://www.mcmod.cn/class/17142.html"), headers
        ));
        assertEquals(Map.of(), SecureWebFetcher.resourceHeaders(
                site, URI.create("https://i.mcmod.cn/class/cover/image.jpg"), headers
        ));
        assertEquals(false, SecureWebFetcher.allowsInitialHost(site, "i.mcmod.cn", false));
        assertEquals(true, SecureWebFetcher.allowsInitialHost(site, "i.mcmod.cn", true));
    }
}
