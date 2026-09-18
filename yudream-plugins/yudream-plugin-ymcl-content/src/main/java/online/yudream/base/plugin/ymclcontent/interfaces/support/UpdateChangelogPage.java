package online.yudream.base.plugin.ymclcontent.interfaces.support;

import online.yudream.base.plugin.ymclcontent.application.service.UpdatePlatformService;
import online.yudream.base.plugin.ymclcontent.domain.UpdateArtifact;
import online.yudream.base.plugin.ymclcontent.domain.UpdateRelease;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 更新日志公开页：无需前端参与的站内自渲染 HTML。
 * 启动器「查看完整更新日志」直接打开；内容与 resolvedNotes 同源（notes 优先，否则分类生成）。
 */
public final class UpdateChangelogPage {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private UpdateChangelogPage() {
    }

    public static String renderIndexPage(List<UpdateRelease> releases, String origin) {
        StringBuilder list = new StringBuilder();
        for (UpdateRelease release : releases) {
            String href = escape(UpdateHttpSupport.absoluteChangelogUrl(origin, release.version()));
            list.append("<li>")
                    .append("<a class=\"ver\" href=\"").append(href).append("\">")
                    .append(escape(release.resolvedTitle()))
                    .append("</a>")
                    .append("<span class=\"meta\">")
                    .append(escape(channelLabel(release.channel())))
                    .append(" · ").append(escape(formatPublishedAt(release.publishedAt())))
                    .append("</span>")
                    .append("</li>\n");
        }
        if (list.isEmpty()) {
            list.append("<li><span class=\"meta\">暂无公开发布</span></li>\n");
        }
        return shell("更新日志", "<nav class=\"index\">\n<h1>YMCL 更新日志</h1>\n<ul>\n" + list + "</ul>\n</nav>");
    }

    public static String renderVersionPage(UpdateRelease release, String origin, UpdatePlatformService updates) {
        StringBuilder body = new StringBuilder();
        body.append("<article>\n<header>\n<h1>").append(escape(release.resolvedTitle())).append("</h1>\n");
        body.append("<p class=\"meta\"><span class=\"tag\">").append(escape(channelLabel(release.channel())))
                .append("</span> · ").append(escape(release.version()))
                .append(" · 发布于 ").append(escape(formatPublishedAt(release.publishedAt())))
                .append("</p>\n</header>\n");

        String notes = release.notes();
        if (notes != null && !notes.isBlank()) {
            body.append("<div class=\"notes\">").append(markdownToHtml(notes)).append("</div>\n");
        } else {
            Map<String, List<String>> grouped = release.normalizedChanges();
            if (grouped.isEmpty()) {
                body.append("<p class=\"meta\">本次发布未填写更新内容。</p>\n");
            } else {
                for (String category : UpdateRelease.CHANGE_CATEGORIES) {
                    List<String> items = grouped.get(category);
                    if (items == null || items.isEmpty()) {
                        continue;
                    }
                    body.append("<section>\n<h2>")
                            .append(escape(UpdateRelease.CHANGE_CATEGORY_LABELS.getOrDefault(category, category)))
                            .append("</h2>\n<ul>\n");
                    for (String item : items) {
                        body.append("<li>").append(inlineHtml(item)).append("</li>\n");
                    }
                    body.append("</ul>\n</section>\n");
                }
            }
        }

        String downloads = renderDownloads(release, origin, updates);
        if (!downloads.isEmpty()) {
            body.append(downloads);
        }
        body.append("</article>\n");
        String title = release.resolvedTitle() + " · 更新日志";
        return shell(title, body.toString());
    }

    public static String renderNotFound(String version) {
        return shell("未找到发布", "<article>\n<h1>未找到发布</h1>\n<p class=\"meta\">版本 "
                + escape(version == null ? "" : version) + " 不存在或未公开。</p>\n</article>");
    }

    private static String renderDownloads(UpdateRelease release, String origin, UpdatePlatformService updates) {
        StringBuilder builder = new StringBuilder();
        for (UpdateArtifact artifact : release.artifacts()) {
            if (UpdateArtifact.KIND_SIGNATURE.equalsIgnoreCase(artifact.kind())) {
                continue;
            }
            String url = updates.resolveDownloadUrl(origin, release, artifact);
            if (url == null || url.isBlank() || artifact.filename() == null || artifact.filename().isBlank()) {
                continue;
            }
            builder.append("<li><a href=\"").append(escape(url)).append("\">")
                    .append(escape(artifact.filename()))
                    .append("</a><span class=\"meta\">")
                    .append(escape(artifact.kind() == null ? "installer" : artifact.kind()));
            if (artifact.platform() != null && !artifact.platform().isBlank()) {
                builder.append(" · ").append(escape(artifact.platform()));
                if (artifact.architecture() != null && !artifact.architecture().isBlank()) {
                    builder.append('/').append(escape(artifact.architecture()));
                }
            }
            if (artifact.size() > 0) {
                builder.append(" · ").append(formatSize(artifact.size()));
            }
            builder.append("</span></li>\n");
        }
        if (builder.isEmpty()) {
            return "";
        }
        return "<section>\n<h2>下载</h2>\n<ul class=\"downloads\">\n" + builder + "</ul>\n</section>\n";
    }

    /** 极简 Markdown：标题/列表/引用/分隔线/代码块 + 行内粗体、斜体、代码、链接。输入不可信内容一律先转义。 */
    static String markdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean inCode = false;
        boolean inUl = false;
        boolean inOl = false;
        StringBuilder paragraph = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.stripTrailing();
            if (line.strip().startsWith("```")) {
                flushParagraph(html, paragraph);
                closeLists(html, inUl, inOl);
                inUl = false;
                inOl = false;
                if (inCode) {
                    html.append("</code></pre>\n");
                    inCode = false;
                } else {
                    html.append("<pre><code>");
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                html.append(escape(line)).append('\n');
                continue;
            }
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                flushParagraph(html, paragraph);
                closeLists(html, inUl, inOl);
                inUl = false;
                inOl = false;
                continue;
            }
            if (stripped.equals("---") || stripped.equals("***")) {
                flushParagraph(html, paragraph);
                closeLists(html, inUl, inOl);
                inUl = false;
                inOl = false;
                html.append("<hr>\n");
                continue;
            }
            String heading = headingLevel(stripped);
            if (heading != null) {
                flushParagraph(html, paragraph);
                closeLists(html, inUl, inOl);
                inUl = false;
                inOl = false;
                html.append(heading);
                continue;
            }
            if (stripped.startsWith("> ")) {
                flushParagraph(html, paragraph);
                closeLists(html, inUl, inOl);
                inUl = false;
                inOl = false;
                html.append("<blockquote>").append(inlineHtml(stripped.substring(2))).append("</blockquote>\n");
                continue;
            }
            boolean ulItem = stripped.startsWith("- ") || stripped.startsWith("* ");
            boolean olItem = isOrderedItem(stripped);
            if (ulItem) {
                flushParagraph(html, paragraph);
                if (inOl) {
                    html.append("</ol>\n");
                    inOl = false;
                }
                if (!inUl) {
                    html.append("<ul>\n");
                    inUl = true;
                }
                html.append("<li>").append(inlineHtml(stripped.substring(2))).append("</li>\n");
                continue;
            }
            if (olItem) {
                flushParagraph(html, paragraph);
                if (inUl) {
                    html.append("</ul>\n");
                    inUl = false;
                }
                if (!inOl) {
                    html.append("<ol>\n");
                    inOl = true;
                }
                html.append("<li>").append(inlineHtml(stripped.substring(stripped.indexOf('.') + 1).strip()))
                        .append("</li>\n");
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append("<br>\n");
            }
            paragraph.append(inlineHtml(stripped));
        }
        flushParagraph(html, paragraph);
        closeLists(html, inUl, inOl);
        if (inCode) {
            html.append("</code></pre>\n");
        }
        return html.toString();
    }

    private static String headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level >= 1 && level <= 4 && level < line.length() && line.charAt(level) == ' ') {
            return "<h" + (level + 1) + ">" + inlineHtml(line.substring(level + 1).strip()) + "</h" + (level + 1)
                    + ">\n";
        }
        return null;
    }

    private static boolean isOrderedItem(String line) {
        int dot = line.indexOf('.');
        if (dot <= 0 || dot + 1 >= line.length() || line.charAt(dot + 1) != ' ') {
            return false;
        }
        for (int index = 0; index < dot; index++) {
            if (!Character.isDigit(line.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            html.append("<p>").append(paragraph).append("</p>\n");
            paragraph.setLength(0);
        }
    }

    private static void closeLists(StringBuilder html, boolean inUl, boolean inOl) {
        if (inUl) {
            html.append("</ul>\n");
        }
        if (inOl) {
            html.append("</ol>\n");
        }
    }

    /** 行内格式：先整体转义，再套用不会被转义破坏的记号（` * [ ] ( )）。 */
    private static String inlineHtml(String text) {
        String escaped = escape(text);
        escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
        escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        escaped = escaped.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
        escaped = escaped.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
        return escaped;
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String channelLabel(String channel) {
        return UpdateRelease.CHANNEL_BETA.equals(channel) ? "测试版" : "正式版";
    }

    private static String formatPublishedAt(String publishedAt) {
        if (publishedAt == null || publishedAt.isBlank()) {
            return "未知时间";
        }
        String value = publishedAt.trim();
        try {
            return DATE_FORMAT.format(Instant.ofEpochMilli(Long.parseLong(value)).atZone(ZoneOffset.UTC));
        } catch (NumberFormatException ignored) {
            try {
                return DATE_FORMAT.format(Instant.parse(value).atZone(ZoneOffset.UTC));
            } catch (Exception ignoredAgain) {
                try {
                    return DATE_FORMAT.format(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC));
                } catch (Exception ignoredFinal) {
                    return value;
                }
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static String shell(String title, String body) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="robots" content="noindex">
                <title>%s</title>
                <style>
                :root { color-scheme: light dark; }
                * { box-sizing: border-box; }
                body {
                  margin: 0; padding: 32px 16px 64px;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
                    "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
                  line-height: 1.7; color: #1f2937; background: #f8fafc;
                }
                .wrap { max-width: 760px; margin: 0 auto; }
                .brand { display: flex; align-items: center; gap: 8px; font-size: 14px; color: #6b7280;
                  margin-bottom: 24px; text-decoration: none; }
                .brand .dot { width: 10px; height: 10px; border-radius: 999px; background: #2563eb; }
                article, .index {
                  background: #fff; border: 1px solid #e5e7eb; border-radius: 12px;
                  padding: 28px 32px; box-shadow: 0 1px 3px rgba(0,0,0,.05);
                }
                h1 { font-size: 26px; margin: 0 0 8px; }
                h2 { font-size: 18px; margin: 24px 0 8px; padding-bottom: 6px; border-bottom: 1px solid #e5e7eb; }
                .meta { color: #6b7280; font-size: 13px; }
                .tag { display: inline-block; padding: 1px 8px; border-radius: 999px; font-size: 12px;
                  border: 1px solid #93c5fd; color: #1d4ed8; background: #eff6ff; }
                ul, ol { padding-left: 22px; margin: 8px 0; }
                li { margin: 4px 0; }
                a { color: #2563eb; }
                .index ul { list-style: none; padding-left: 0; }
                .index li { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap;
                  padding: 10px 0; border-bottom: 1px dashed #e5e7eb; }
                .index .ver { font-weight: 600; }
                pre { background: #f1f5f9; border-radius: 8px; padding: 12px 14px; overflow-x: auto;
                  font-size: 13px; }
                code { background: #f1f5f9; border-radius: 4px; padding: 1px 5px; font-size: 90%%; }
                pre code { background: none; padding: 0; }
                blockquote { margin: 8px 0; padding: 4px 14px; border-left: 3px solid #cbd5e1;
                  color: #6b7280; }
                .downloads li { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
                footer { margin-top: 24px; text-align: center; font-size: 12px; color: #9ca3af; }
                @media (prefers-color-scheme: dark) {
                  body { color: #d1d5db; background: #0b1120; }
                  article, .index { background: #111827; border-color: #1f2937;
                    box-shadow: none; }
                  h2 { border-bottom-color: #1f2937; }
                  .tag { border-color: #1e40af; color: #93c5fd; background: #1e293b; }
                  pre, code { background: #1e293b; }
                  .index li { border-bottom-color: #1f2937; }
                  blockquote { border-left-color: #374151; }
                  a { color: #60a5fa; }
                }
                </style>
                </head>
                <body>
                <div class="wrap">
                <a class="brand" href="./"><span class="dot"></span>YMCL 更新平台</a>
                %s
                <footer>由 YMCL 更新平台自动生成</footer>
                </div>
                </body>
                </html>
                """.formatted(escape(title), body);
    }
}
