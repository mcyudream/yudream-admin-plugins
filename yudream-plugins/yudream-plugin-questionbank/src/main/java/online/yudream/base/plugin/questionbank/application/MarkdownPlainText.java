package online.yudream.base.plugin.questionbank.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → 纯文本简化器，用于 Word 导出与 QQ 消息。
 * 宿主 Word 渲染器不支持值内换行（run 不拆 \n），因此输出按行组织，
 * 由调用方决定拼接方式；图片保留「[图片:alt]」占位文本，链接只留显示文本。
 */
public final class MarkdownPlainText {
    private static final Pattern IMAGE = Pattern.compile("!\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s*");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?");
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern BOLD_ITALIC = Pattern.compile("(\\*{1,3}|_{1,3}|~~)(.*?)\\1");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?[\\s:|-]+\\|?\\s*$");

    private MarkdownPlainText() {
    }

    /** 转为单行纯文本（Word 占位符安全）：逐行清理后以空格拼接。 */
    public static String toSingleLine(String markdown) {
        return String.join(" ", toLines(markdown));
    }

    /** 逐行纯文本（QQ 消息等允许换行的渠道用 \n 拼接）。 */
    public static String toMultiline(String markdown) {
        return String.join("\n", toLines(markdown));
    }

    public static List<String> toLines(String markdown) {
        List<String> lines = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return lines;
        }
        boolean inFence = false;
        for (String raw : markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            String line = raw;
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence) {
                line = HEADING.matcher(line).replaceFirst("");
                line = QUOTE.matcher(line).replaceFirst("");
                line = LIST_MARKER.matcher(line).replaceFirst("· ");
                if (TABLE_SEPARATOR.matcher(line).matches() && line.contains("-")) {
                    continue;
                }
                line = line.replace('|', ' ');
                line = IMAGE.matcher(line).replaceAll(matchResult -> {
                    String alt = matchResult.group(1) == null ? "" : matchResult.group(1).trim();
                    return Matcher.quoteReplacement(alt.isEmpty() ? "[图片]" : "[图片:" + alt + "]");
                });
                line = LINK.matcher(line).replaceAll("$1");
                line = BOLD_ITALIC.matcher(line).replaceAll("$2");
                line = INLINE_CODE.matcher(line).replaceAll("$1");
            }
            line = line.trim();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
