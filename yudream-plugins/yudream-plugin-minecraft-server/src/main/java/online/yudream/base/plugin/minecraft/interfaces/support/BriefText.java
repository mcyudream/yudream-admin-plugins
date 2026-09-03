package online.yudream.base.plugin.minecraft.interfaces.support;

import java.util.regex.Pattern;

/**
 * 从 Markdown 描述推导纯文本简述，供 QQ 回复、状态渲染图和 AI 工具结果等空间受限场景使用。
 * 处理分两步：剥除 Markdown 标记并压缩空白；超过上限时按标点边界截断并追加省略号。
 */
public final class BriefText {

    public static final int DEFAULT_LIMIT = 120;

    private static final Pattern CODE_FENCE = Pattern.compile("(?s)```[a-zA-Z0-9+-]*\\s*(.*?)```");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");
    private static final Pattern REFERENCE_LINK = Pattern.compile("\\[([^\\]]+)\\]\\[[^\\]]*\\]");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+");
    private static final Pattern QUOTE = Pattern.compile("(?m)^\\s{0,3}>\\s?");
    private static final Pattern LIST_ITEM = Pattern.compile("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern TASK_MARK = Pattern.compile("\\[[ xX]\\]\\s*");
    private static final Pattern STRIKETHROUGH = Pattern.compile("~~(.*?)~~");
    private static final Pattern EMPHASIS_STRONG = Pattern.compile("(\\*\\*|__)(.*?)\\1");
    private static final Pattern EMPHASIS = Pattern.compile("(?<!\\w)[*_]([^*_]+)[*_](?!\\w)");
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]{0,200}>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[\\s，。；、,.;：:]+$");
    private static final String BREAK_CHARS = " ，。；、,.;：:";

    private BriefText() {
    }

    public static String ofMarkdown(String markdown) {
        return ofMarkdown(markdown, DEFAULT_LIMIT);
    }

    public static String ofMarkdown(String markdown, int maxChars) {
        if (markdown == null || markdown.isBlank() || maxChars <= 0) {
            return "";
        }
        String text = CODE_FENCE.matcher(markdown).replaceAll("$1");
        text = IMAGE.matcher(text).replaceAll("$1");
        text = LINK.matcher(text).replaceAll("$1");
        text = REFERENCE_LINK.matcher(text).replaceAll("$1");
        text = HEADING.matcher(text).replaceAll("");
        text = QUOTE.matcher(text).replaceAll("");
        text = LIST_ITEM.matcher(text).replaceAll("");
        text = TASK_MARK.matcher(text).replaceAll("");
        text = STRIKETHROUGH.matcher(text).replaceAll("$1");
        text = EMPHASIS_STRONG.matcher(text).replaceAll("$2");
        text = EMPHASIS.matcher(text).replaceAll("$1");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = text.replace('`', ' ');
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        return truncate(text, maxChars);
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = maxChars;
        int floor = Math.max(maxChars * 2 / 3, 1);
        for (int i = maxChars - 1; i >= floor; i--) {
            if (BREAK_CHARS.indexOf(text.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        return TRAILING_PUNCT.matcher(text.substring(0, cut)).replaceAll("") + "…";
    }
}
