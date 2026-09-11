package online.yudream.base.plugin.mcnews.application;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import online.yudream.base.plugin.mcnews.domain.NewsArticle;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatRequest;
import online.yudream.base.plugin.spi.system.ai.PluginAiChatResponse;
import online.yudream.base.plugin.spi.system.ai.PluginAiExecutionContext;
import online.yudream.base.plugin.spi.system.ai.PluginAiService;

/**
 * AI 新闻导语整合：调用宿主 AI 能力将标题/摘要整合成中文导语。
 * AI 能力缺失、超时或输出为空时返回 Optional.empty()，由调用方回退官方摘要。
 */
public final class NewsAiSummarizer {
    private static final Logger LOGGER = Logger.getLogger(NewsAiSummarizer.class.getName());
    private static final long TIMEOUT_SECONDS = 60;

    private final FrameworkServices framework;
    private final McNewsSettings settings;

    public NewsAiSummarizer(FrameworkServices framework, McNewsSettings settings) {
        this.framework = framework;
        this.settings = settings;
    }

    /**
     * 整合一条新闻：标题/摘要之外还传入正文节选，让导语保留具体修复项与变更点。
     * AI 能力缺失、超时或输出为空时返回 Optional.empty()，由调用方回退官方摘要。
     */
    public Optional<String> summarize(NewsArticle article, String content, String mode, String traceId) {
        PluginAiService ai;
        try {
            ai = framework.ai();
        }
        catch (Throwable e) {
            return Optional.empty();
        }
        if (ai == null) {
            return Optional.empty();
        }
        String excerpt = truncate(content, settings.aiContentMaxChars());
        String userPrompt = "【标题】" + safe(article.title())
                + "\n【分类】" + safe(article.category())
                + "\n【来源】" + safe(article.sourceName())
                + "\n【官方摘要】" + (article.summary() == null || article.summary().isBlank() ? "（无）" : article.summary())
                + "\n【正文节选】" + (excerpt.isBlank() ? "（未获取到正文）" : excerpt);
        PluginAiExecutionContext context = new PluginAiExecutionContext(null, null, null, null, null,
                "MCNEWS_" + mode.toUpperCase(), traceId, List.of());
        try {
            PluginAiChatResponse response = ai.chat(new PluginAiChatRequest(
                    settings.aiSystemPrompt(), userPrompt,
                    settings.aiProviderCode(), settings.aiModelCode(),
                    List.of(), context, false))
                    .toCompletableFuture()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response == null || response.content() == null || response.content().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(response.content().trim());
        }
        catch (CompletionException e) {
            LOGGER.log(Level.WARNING, "[MC News] AI 整合调用失败，本条回退官方摘要", e.getCause() == null ? e : e.getCause());
            return Optional.empty();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        catch (Exception e) {
            LOGGER.log(Level.WARNING, "[MC News] AI 整合调用失败，本条回退官方摘要", e);
            return Optional.empty();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "\n…（正文已截断）";
    }
}
