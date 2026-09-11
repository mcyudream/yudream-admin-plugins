package online.yudream.base.plugin.mcnews.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NewsTemplateServiceTest {

    private final NewsTemplateService templates = new NewsTemplateService();

    @Test
    void replacesKnownVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("title", "Java 版更新");
        vars.put("url", "https://www.minecraft.net/article/a");
        String rendered = NewsTemplateService.replaceVars("【{{title}}】 {{url}} {{unknown}}", vars);
        assertEquals("【Java 版更新】 https://www.minecraft.net/article/a ", rendered);
    }

    @Test
    void toleratesUnclosedAndSpacedPlaceholders() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("title", "A");
        assertEquals("A {{", NewsTemplateService.replaceVars("{{title}} {{", vars));
        assertEquals("A|", NewsTemplateService.replaceVars("{{ title }}|", vars));
    }

    @Test
    void renderCollapsesBlankLinesAndTrims() {
        online.yudream.base.plugin.mcnews.domain.NewsArticle article =
                new online.yudream.base.plugin.mcnews.domain.NewsArticle("id", "s", "源", "标题",
                        "摘要", "", "News", "https://example.com/a", "", 0, 0, 0, "");
        String rendered = templates.render("📰 {{title}}\n\n\n\n{{body}}\n\n\n", article);
        assertEquals("📰 标题\n\n摘要", rendered);
    }

    @Test
    void bodyPrefersAiSummaryThenSummary() {
        online.yudream.base.plugin.mcnews.domain.NewsArticle withAi =
                new online.yudream.base.plugin.mcnews.domain.NewsArticle("id", "s", "源", "标题",
                        "摘要", "AI 导语", "", "", "", 0, 0, 0, "");
        assertEquals("AI 导语", withAi.body());
        online.yudream.base.plugin.mcnews.domain.NewsArticle fallback =
                new online.yudream.base.plugin.mcnews.domain.NewsArticle("id", "s", "源", "标题",
                        "摘要", "", "", "", "", 0, 0, 0, "");
        assertEquals("摘要", fallback.body());
    }

    @Test
    void variablesCatalogContainsCoreVars() {
        assertTrue(NewsTemplateService.VARIABLES.stream().anyMatch(v -> v.name().equals("body")));
        assertFalse(NewsTemplateService.VARIABLES.isEmpty());
    }

    @Test
    void previewRendersSampleData() {
        String preview = templates.preview("📰 {{title}}\n{{body}}");
        assertTrue(preview.startsWith("📰 "));
        assertFalse(preview.contains("{{"));
    }
}
