package online.yudream.plugin.webcard.infrastructure;

import online.yudream.plugin.webcard.domain.WebCardModels.TemplateMode;
import online.yudream.plugin.webcard.domain.WebCardModels.TemplateVersion;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardRendererStructuredLayoutTest {
    private final CardRenderer renderer = new CardRenderer(null);

    @Test
    void structuredJsonControlsVariantVisibilityAndExtraFields() {
        TemplateVersion template = template("""
                {
                  "variant": "compact",
                  "accentColor": "#176b87",
                  "showImage": false,
                  "showSource": false,
                  "showSummary": false,
                  "showUrl": false,
                  "extraFields": ["version"]
                }
                """);

        String html = renderer.renderHtml(template, Map.of(
                "title", "Project", "summary", "Hidden summary", "source", "example.com",
                "url", "https://example.com/1", "image", "https://example.com/cover.png",
                "version", "1.20.1", "author", "Hidden author"));

        assertTrue(html.contains("variant-compact no-media"));
        assertTrue(html.contains("--accent:#176b87"));
        assertTrue(html.contains("1.20.1"));
        assertFalse(html.contains("Hidden summary"));
        assertFalse(html.contains("Hidden author"));
        assertFalse(html.contains("class=\"eyebrow\""));
        assertFalse(html.contains("class=\"url\""));
    }

    @Test
    void invalidStructuredJsonIsRejectedByRenderer() {
        assertThrows(IllegalArgumentException.class, () -> renderer.renderHtml(template("{not-json"), Map.of("title", "Project")));
    }

    @Test
    void dynamicSectionsRenderStructuredMapsListsAndLinkLabels() {
        TemplateVersion template = template("""
                {
                  "variant": "editorial",
                  "sections": [
                    {"title":"核心信息","layout":"grid","fields":["基础信息"]},
                    {"title":"支持版本","layout":"chips","fields":["支持版本"]},
                    {"title":"作者与链接","layout":"links","fields":["作者","相关链接"]}
                  ]
                }
                """);

        String html = renderer.renderHtml(template, Map.of(
                "title", "更多进度",
                "基础信息", Map.of("支持平台", "JAVA版", "运作方式", "数据包"),
                "支持版本", List.of("1.21.11", "1.21.10"),
                "作者", List.of("MC的小方块"),
                "相关链接", List.of(Map.of("label", "Modrinth", "url", "https://modrinth.com/example"))
        ));

        assertTrue(html.contains("核心信息"));
        assertTrue(html.contains("支持平台"));
        assertTrue(html.contains("JAVA版"));
        assertTrue(html.contains("1.21.11"));
        assertTrue(html.contains("MC的小方块"));
        assertTrue(html.contains("Modrinth"));
        assertFalse(html.contains("{label="));
        assertFalse(html.contains("[object Object]"));
    }

    @Test
    void oversizedAdvancedTemplateDimensionsAreRejectedBeforeRendering() {
        TemplateVersion template = new TemplateVersion(null, "template", 0, null, TemplateMode.ADVANCED, "{}",
                "<article id=\"web-card\">Test</article>", "#web-card{width:100000px}", Map.of(), "TEST", "", false, 0);
        assertThrows(IllegalArgumentException.class, () -> renderer.renderHtml(template, Map.of()));
    }

    @Test
    void advancedTemplatesCannotLoadExternalResources() {
        TemplateVersion template = new TemplateVersion(null, "template", 0, null, TemplateMode.ADVANCED, "{}",
                "<article id=\"web-card\"><img src=\"http://169.254.169.254/latest/meta-data\"></article>",
                "#web-card{width:640px}", Map.of(), "TEST", "", false, 0);

        String html = renderer.renderHtml(template, Map.of());

        assertFalse(html.contains("169.254.169.254"));
        TemplateVersion cssUrl = new TemplateVersion(null, "template", 0, null, TemplateMode.ADVANCED, "{}",
                "<article id=\"web-card\">Test</article>", "#web-card{background:url(https://example.com/x.png)}", Map.of(), "TEST", "", false, 0);
        assertThrows(IllegalArgumentException.class, () -> renderer.renderHtml(cssUrl, Map.of()));
        TemplateVersion styleBreakout = new TemplateVersion(null, "template", 0, null, TemplateMode.ADVANCED, "{}",
                "<article id=\"web-card\">Test</article>", "</style><script>alert(1)</script><style>", Map.of(), "TEST", "", false, 0);
        assertThrows(IllegalArgumentException.class, () -> renderer.renderHtml(styleBreakout, Map.of()));
    }

    private TemplateVersion template(String layout) {
        return new TemplateVersion(null, "template", 0, null, TemplateMode.STRUCTURED, layout,
                "", "", Map.of(), "TEST", "", false, 0);
    }
}
