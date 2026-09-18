package online.yudream.base.plugin.ymclcontent.interfaces.support;

import online.yudream.base.plugin.ymclcontent.application.service.UpdatePlatformService;
import online.yudream.base.plugin.ymclcontent.domain.UpdateArtifact;
import online.yudream.base.plugin.ymclcontent.domain.UpdateRelease;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateChangelogPageTest {

    @Test
    void markdownRendersHeadingsListsAndInlineStyles() {
        String html = UpdateChangelogPage.markdownToHtml(
                "## 标题\n\n- **重点** `code`\n- [链接](https://example.com/a?b=1)\n\n1. 第一\n2. 第二");
        assertTrue(html.contains("<h3>标题</h3>"));
        assertTrue(html.contains("<ul>"));
        assertTrue(html.contains("<strong>重点</strong>"));
        assertTrue(html.contains("<code>code</code>"));
        assertTrue(html.contains("<a href=\"https://example.com/a?b=1\">链接</a>"));
        assertTrue(html.contains("<ol>"));
        assertFalse(html.contains("<script"));
    }

    @Test
    void markdownEscapesUntrustedContent() {
        String html = UpdateChangelogPage.markdownToHtml("<script>alert(1)</script>\n\n- <img src=x>");
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("<img"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;img src=x&gt;"));
    }

    @Test
    void markdownSupportsCodeFenceAndQuote() {
        String html = UpdateChangelogPage.markdownToHtml("```\n<b>raw</b>\n```\n\n> 引用 *斜体*");
        assertTrue(html.contains("<pre><code>&lt;b&gt;raw&lt;/b&gt;"));
        assertTrue(html.contains("</code></pre>"));
        assertTrue(html.contains("<blockquote>引用 <em>斜体</em></blockquote>"));
    }

    @Test
    void versionPageRendersWithPercentInCssAndDownloads() {
        UpdateArtifact artifact = new UpdateArtifact(
                "art-1", "installer", "nsis", "windows", "x86_64", List.of(),
                "YMCL-Setup.exe", null, "ymcl-update/files/1.2.0/YMCL-Setup.exe",
                "abc", 1048576L, "sig-data", "application/octet-stream", "1");
        UpdateRelease release = new UpdateRelease(
                "1.2.0", "release", null, null,
                Map.of("added", List.of("新功能 <b>加粗</b>")), "1739900000000",
                false, false, true, null, List.of(artifact), "1");
        UpdatePlatformService updates = new UpdatePlatformService(null, null) {
            @Override
            public String resolveDownloadUrl(String origin, UpdateRelease rel, UpdateArtifact art) {
                return "https://downloads.example.com/YMCL-Setup.exe";
            }
        };
        String html = UpdateChangelogPage.renderVersionPage(release, "https://ymcl.example.com", updates);
        // CSS 里的字面量 % 不得破坏 formatted()
        assertTrue(html.contains("font-size: 90%"));
        assertTrue(html.contains("<h1>YMCL 1.2.0</h1>"));
        assertTrue(html.contains("<h2>新增</h2>"));
        assertTrue(html.contains("新功能 &lt;b&gt;加粗&lt;/b&gt;"));
        assertTrue(html.contains("1.0 MB"));
        assertTrue(html.contains("https://downloads.example.com/YMCL-Setup.exe"));
        assertFalse(html.contains("<b>加粗</b>"));
    }

    @Test
    void indexPageListsVisibleReleases() {
        UpdateRelease release = new UpdateRelease(
                "1.1.0", "beta", null, null, Map.of(), "2026-01-02T03:04:05Z",
                false, false, true, null, List.of(), "1");
        String html = UpdateChangelogPage.renderIndexPage(List.of(release), "https://ymcl.example.com");
        assertTrue(html.contains("YMCL 1.1.0"));
        assertTrue(html.contains("测试版"));
        assertTrue(html.contains("https://ymcl.example.com/api/plugins/ymcl-content/v1/update/changelog/1.1.0"));
    }
}
