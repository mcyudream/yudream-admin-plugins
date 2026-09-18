package online.yudream.base.plugin.mcnews.domain;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 内容指纹：判定「同一篇新闻」的依据是规范化后的**标题**与**链接**，任一命中即视为同一篇。
 * 标题的大小写/标点/空白差异、链接的 query、fragment、尾斜杠、语言前缀差异都不会造成重复推送。
 * 持久化形态为单行文本 {@code id \t 标题键 \t 链接键}，反解即得该条目的全部匹配键；
 * 兼容历史数据（只存原始 id 的忽略名单）。
 */
public final class NewsFingerprint {
    private static final char FIELD_SEP = '\t';
    private static final String TITLE_PREFIX = "t:";
    private static final String URL_PREFIX = "u:";

    /**
     * 语言前缀：/zh-hans/article/x、/en-us/article/x 归一到 /article/x。
     * 仅在后面紧跟 article / hc 路径时剥离，避免误吃普通路径段。
     */
    private static final Pattern LANGUAGE_PREFIX =
            Pattern.compile("^([^/]+)/[a-z]{2}(?:-[a-z0-9]{2,8})?/(?=(?:article|hc)/)");

    private NewsFingerprint() {
    }

    /** 生成持久化条目；标题或链接缺失时对应字段留空，反解时忽略。 */
    public static String entry(NewsArticle article) {
        return entry(article.id(), article.title(), article.url());
    }

    public static String entry(String id, String title, String url) {
        return safe(id) + FIELD_SEP + titleKey(title) + FIELD_SEP + urlKey(url);
    }

    /** 条目的原始 id（持久化条目的第一段）。 */
    public static String entryId(String stored) {
        if (stored == null) {
            return "";
        }
        int separator = stored.indexOf(FIELD_SEP);
        return (separator < 0 ? stored : stored.substring(0, separator)).trim();
    }

    /** 条目集合里的全部原始 id。 */
    public static Set<String> entryIds(Collection<String> stored) {
        Set<String> ids = new LinkedHashSet<>();
        for (String entry : stored) {
            String id = entryId(entry);
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 候选条目的全部匹配键：原始 id + 标题键 + 链接键。 */
    public static Set<String> keysOf(NewsArticle article) {
        return keysOfEntry(entry(article));
    }

    /** 反解持久化条目为匹配键集合；保留原始 id 保证与历史忽略名单兼容。 */
    public static Set<String> keysOfEntry(String stored) {
        Set<String> keys = new LinkedHashSet<>();
        if (stored == null || stored.isBlank()) {
            return keys;
        }
        String[] fields = stored.split(String.valueOf(FIELD_SEP), -1);
        String id = fields.length > 0 ? fields[0].trim() : "";
        if (!id.isEmpty()) {
            keys.add(id);
        }
        if (fields.length > 1 && !fields[1].isBlank()) {
            keys.add(TITLE_PREFIX + fields[1].trim());
        }
        if (fields.length > 2 && !fields[2].isBlank()) {
            keys.add(URL_PREFIX + fields[2].trim());
        }
        return keys;
    }

    /** 展开一批持久化条目为匹配键集合。 */
    public static Set<String> flatten(Collection<String> storedEntries) {
        Set<String> keys = new LinkedHashSet<>();
        for (String stored : storedEntries) {
            keys.addAll(keysOfEntry(stored));
        }
        return keys;
    }

    /**
     * 标题键：全角折半角、转小写、非字母数字统一压成单个空格。
     * 抹平大小写/标点/空白差异，同时保留数字分段（1.21 与 12.1 不会混为一条）。
     */
    public static String titleKey(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && result.length() > 0) {
                    result.append(' ');
                }
                pendingSpace = false;
                result.append(c);
            }
            else {
                pendingSpace = true;
            }
        }
        return result.toString();
    }

    /**
     * 链接键：去 fragment、去 query、去协议与 www、去尾斜杠、去语言前缀、整体小写。
     * 同一篇文章换语言站、带统计参数、被补上尾斜杠时键保持一致。
     */
    public static String urlKey(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String value = url.trim();
        int cut = value.indexOf('#');
        if (cut >= 0) {
            value = value.substring(0, cut);
        }
        cut = value.indexOf('?');
        if (cut >= 0) {
            value = value.substring(0, cut);
        }
        value = value.toLowerCase(Locale.ROOT);
        if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        }
        else if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        }
        if (value.startsWith("www.")) {
            value = value.substring("www.".length());
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return LANGUAGE_PREFIX.matcher(value).replaceFirst("$1/");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
