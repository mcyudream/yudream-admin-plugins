package online.yudream.base.plugin.eduverify.infrastructure.chsi;

import online.yudream.base.plugin.eduverify.application.chsi.ChsiReportParser;
import online.yudream.base.plugin.eduverify.application.chsi.ChsiVerifier;
import online.yudream.base.plugin.spi.system.render.PluginRenderedPage;
import online.yudream.base.plugin.spi.system.render.PluginRenderService;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 经宿主通用网页抓取能力读取学信网公开报告页，再按可配置 CSS 选择器解析。
 */
public class OfficialPageChsiVerifier implements ChsiVerifier {

    private static final long FETCH_TIMEOUT_SECONDS = 40L;

    private final PluginRenderService render;

    public OfficialPageChsiVerifier(PluginRenderService render) {
        this.render = render;
    }

    @Override
    public ChsiVerifyResult verify(String vcode, ChsiParseConfig config) {
        if (render == null) {
            return ChsiVerifyResult.unavailable("宿主未提供网页抓取能力，请开启消息渲染或改走人工审核");
        }
        String url = ChsiReportParser.buildUrl(config.urlTemplate(), vcode);
        PluginRenderedPage page;
        try {
            page = render.htmlFromUrl(url).toCompletableFuture().get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return ChsiVerifyResult.unavailable("学信网报告页读取超时，请稍后重试或改走人工审核");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChsiVerifyResult.unavailable("学信网报告页读取被中断");
        } catch (Exception e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            if (message.contains("UnsupportedOperationException") || message.contains("htmlFromUrl is unavailable")
                    || message.contains("未启用") || message.contains("不可用")) {
                return ChsiVerifyResult.unavailable("宿主网页抓取能力未启用，请开启消息渲染或改走人工审核");
            }
            return ChsiVerifyResult.failed("读取学信网报告页失败：" + message);
        }
        if (page == null || page.html() == null || page.html().isBlank()) {
            return ChsiVerifyResult.failed("学信网报告页为空");
        }
        return ChsiReportParser.parse(page.html(), vcode, config);
    }
}
