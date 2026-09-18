package online.yudream.base.plugin.mcnews.domain;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 关键词模板规则：把新闻源上的「一条关键词」编译成可复用的匹配器，支持正则、通配等形式。
 *
 * <p>书写形式 {@code [!][字段:][类型:]模式}，各段均可省略：
 * <ul>
 *   <li><b>排除</b>：以 {@code !}（或全角 {@code ！}）开头表示「命中即丢弃」，优先级高于收录规则。</li>
 *   <li><b>字段</b>：{@code title:} 标题、{@code summary:} 摘要、{@code url:} 链接、{@code any:} 标题+摘要（缺省）。</li>
 *   <li><b>类型</b>：{@code text:} 子串（缺省）、{@code re:} 正则、{@code glob:} 通配（{@code *} 任意、{@code ?} 单字符）。
 *       不写类型时，{@code /正则/标志} 会自动识别为正则，其余按字面量处理。</li>
 * </ul>
 *
 * <p>字段与类型前缀可自由组合、顺序任意，例如 {@code title:re:^Version}；
 * <strong>未写类型时一律按字面量处理</strong>：{@code title:^Version} 匹配的是标题里真的出现
 * {@code ^Version} 这几个字符，想做正则匹配必须写 {@code title:re:^Version}。
 *
 * <p>全部规则默认<strong>大小写不敏感</strong>；正则需区分大小写时用内联标志 {@code (?-i)}。
 * 三类匹配都使用「包含」语义（{@code find()}）：正则要整串匹配请自行加 {@code ^...$}。
 *
 * <p>示例：{@code A Minecraft Java}、{@code re:^Release\s+\d+}、{@code /snapshot/i}、
 * {@code glob:*Preview*}、{@code title:re:^Version}、{@code !re:redstone}。
 *
 * <p>非法规则在保存新闻源时即抛 {@link IllegalArgumentException}（HTTP 400）；运行时使用
 * {@link NewsKeywordFilter#lenient} 跳过非法规则，避免一条写错导致整个源失效。
 */
public record NewsKeywordRule(String raw, Field field, Kind kind, boolean exclude, Pattern pattern) {

    /** 单条模板长度上限：正则回溯风险随模式长度上升，标题/摘要本身很短，限制长度即足够。 */
    public static final int MAX_PATTERN_LENGTH = 300;

    /** 每个新闻源的模板条数上限。 */
    public static final int MAX_RULES = 40;

    /** 匹配字段。 */
    public enum Field {
        ANY("标题 + 摘要"),
        TITLE("标题"),
        SUMMARY("摘要"),
        URL("链接");

        private final String label;

        Field(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static Field of(String token) {
            return switch (token) {
                case "any", "all" -> ANY;
                case "title" -> TITLE;
                case "summary", "desc" -> SUMMARY;
                case "url", "link" -> URL;
                default -> null;
            };
        }
    }

    /** 匹配类型。 */
    public enum Kind {
        TEXT("子串"),
        REGEX("正则"),
        GLOB("通配");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static Kind of(String token) {
            return switch (token) {
                case "text", "word", "literal" -> TEXT;
                case "re", "regex", "regexp" -> REGEX;
                case "glob", "wildcard" -> GLOB;
                default -> null;
            };
        }
    }

    /** 解析并编译一条模板；非法时抛出带可读原因的 {@link IllegalArgumentException}。 */
    public static NewsKeywordRule parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("关键词模板不能为空");
        }
        boolean exclude = false;
        if (text.startsWith("!") || text.startsWith("！")) {
            exclude = true;
            text = text.substring(1).trim();
        }

        Field field = Field.ANY;
        Kind kind = Kind.TEXT;
        while (true) {
            int colon = text.indexOf(':');
            if (colon <= 0) {
                break;
            }
            String token = text.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            Field parsedField = Field.of(token);
            Kind parsedKind = Kind.of(token);
            if (parsedField == null && parsedKind == null) {
                // 未知前缀（例如 http:// 的冒号）按模式内容处理
                break;
            }
            if (parsedField != null) {
                field = parsedField;
            }
            else {
                kind = parsedKind;
            }
            text = text.substring(colon + 1).trim();
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException("关键词模板缺少匹配内容");
        }

        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        String body = text;
        if (kind == Kind.TEXT) {
            SlashForm slash = SlashForm.split(text);
            if (slash != null) {
                kind = Kind.REGEX;
                body = slash.body();
                flags |= slash.flags();
            }
        }
        if (body.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("关键词模板过长（上限 " + MAX_PATTERN_LENGTH + " 字符）");
        }
        try {
            Pattern pattern = switch (kind) {
                case TEXT -> Pattern.compile(Pattern.quote(body), flags);
                case REGEX -> Pattern.compile(body, flags);
                case GLOB -> Pattern.compile(globToRegex(body), flags);
            };
            return new NewsKeywordRule(raw.trim(), field, kind, exclude, pattern);
        }
        catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("正则表达式不合法：" + e.getDescription());
        }
    }

    /** 该规则是否命中：在规则限定的字段上做一次「包含」匹配。 */
    public boolean matches(NewsArticle article) {
        if (article == null) {
            return false;
        }
        String haystack = switch (field) {
            case TITLE -> article.title();
            case SUMMARY -> article.summary();
            case URL -> article.url();
            case ANY -> text(article.title()) + "\n" + text(article.summary());
        };
        return haystack != null && !haystack.isEmpty() && pattern.matcher(haystack).find();
    }

    /** 供管理端展示的规则摘要，如「排除 标题 正则 re:foo」。 */
    public String describe() {
        return (exclude ? "排除 " : "") + field.label() + " " + kind.label();
    }

    /** 通配转正则：先整体转义，再把 {@code *} / {@code ?} 还原为不跨行的通配。 */
    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? "[^\\n]*" : "[^\\n]");
            }
            else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * {@code /正则/标志} 形式：标志位与 Java 内联标志一致，支持 {@code i}（忽略大小写，默认已开）、
     * {@code m}（多行）、{@code s}（点匹配换行）、{@code u}（Unicode 大小写）。
     * 不含合法标志时返回 null，回退为字面量匹配，避免把 {@code /api/} 这类文本误读成正则。
     */
    private record SlashForm(String body, int flags) {
        static SlashForm split(String text) {
            if (text.length() < 3 || !text.startsWith("/")) {
                return null;
            }
            int end = text.lastIndexOf('/');
            if (end <= 0) {
                return null;
            }
            String flagText = text.substring(end + 1).trim();
            int flags = 0;
            for (int i = 0; i < flagText.length(); i++) {
                int bit = switch (flagText.charAt(i)) {
                    case 'i' -> Pattern.CASE_INSENSITIVE;
                    case 'm' -> Pattern.MULTILINE;
                    case 's' -> Pattern.DOTALL;
                    case 'u' -> Pattern.UNICODE_CASE;
                    default -> -1;
                };
                if (bit < 0) {
                    return null;
                }
                flags |= bit;
            }
            return new SlashForm(text.substring(1, end), flags);
        }
    }
}
