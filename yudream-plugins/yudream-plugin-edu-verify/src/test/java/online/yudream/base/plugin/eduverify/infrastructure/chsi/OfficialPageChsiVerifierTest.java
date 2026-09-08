package online.yudream.base.plugin.eduverify.infrastructure.chsi;

import online.yudream.base.plugin.eduverify.application.chsi.ChsiReportParser;
import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier.ChsiParseConfig;
import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier.ChsiVerifyResult;
import online.yudream.base.plugin.spi.system.render.PluginRenderedPage;
import online.yudream.base.plugin.spi.system.render.PluginRenderService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialPageChsiVerifierTest {

    @Test
    void parsesFetchedOfficialPage() {
        AtomicReference<String> requested = new AtomicReference<>();
        PluginRenderService render = new PluginRenderService() {
            @Override
            public CompletionStage<online.yudream.base.plugin.spi.system.render.PluginRenderedImage> html(String html) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
            }

            @Override
            public CompletionStage<online.yudream.base.plugin.spi.system.render.PluginRenderedImage> markdown(String markdown) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
            }

            @Override
            public CompletionStage<online.yudream.base.plugin.spi.system.render.PluginRenderedImage> url(String url) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
            }

            @Override
            public CompletionStage<PluginRenderedPage> htmlFromUrl(String url) {
                requested.set(url);
                String html = """
                        <html><head><title>教育部学籍在线验证报告</title></head>
                        <body>
                        <table>
                          <tr><td>姓名</td><td>郭金龙</td></tr>
                          <tr><td>学校名称</td><td>西南科技大学</td></tr>
                          <tr><td>学籍状态</td><td>在籍（注册学籍）</td></tr>
                          <tr><td>在线验证码</td><td>APEVUKH9C8SSGS5D</td></tr>
                        </table>
                        </body></html>
                        """;
                return CompletableFuture.completedFuture(new PluginRenderedPage(html, url));
            }
        };
        OfficialPageChsiVerifier verifier = new OfficialPageChsiVerifier(render);
        ChsiVerifyResult result = verifier.verify("APEVUKH9C8SSGS5D",
                new ChsiParseConfig(ChsiReportParser.DEFAULT_URL_TEMPLATE, ChsiReportParser.defaultSelectors()));
        assertEquals("PASSED", result.kind());
        assertEquals("郭金龙", result.realName());
        assertEquals("西南科技大学", result.schoolName());
        assertTrue(requested.get().contains("vcode=APEVUKH9C8SSGS5D"));
        assertTrue(requested.get().contains("srcid=bgcx"));
    }

    @Test
    void reportsUnavailableWhenHostCapabilityMissing() {
        OfficialPageChsiVerifier verifier = new OfficialPageChsiVerifier(null);
        ChsiVerifyResult result = verifier.verify("APEVUKH9C8SSGS5D",
                new ChsiParseConfig(null, null));
        assertEquals("UNAVAILABLE", result.kind());
        assertTrue(result.message().contains("未提供"));
    }

    @Test
    void reportsUnavailableWhenHtmlFromUrlUnsupported() {
        PluginRenderService render = new PluginRenderService() {
            @Override
            public CompletionStage<online.yudream.base.plugin.spi.system.render.PluginRenderedImage> html(String html) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
            }

            @Override
            public CompletionStage<online.yudream.base.plugin.spi.system.render.PluginRenderedImage> markdown(String markdown) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
            }

            @Override
            public CompletionStage<online.yudream.base.plugin.spi.system.render.PluginRenderedImage> url(String url) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("unused"));
            }
        };
        OfficialPageChsiVerifier verifier = new OfficialPageChsiVerifier(render);
        ChsiVerifyResult result = verifier.verify("APEVUKH9C8SSGS5D",
                new ChsiParseConfig(null, null));
        assertEquals("UNAVAILABLE", result.kind());
        assertTrue(result.message().contains("未启用") || result.message().contains("htmlFromUrl"));
    }
}
