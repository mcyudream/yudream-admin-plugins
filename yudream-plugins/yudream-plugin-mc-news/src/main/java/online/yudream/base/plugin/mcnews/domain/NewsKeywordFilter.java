package online.yudream.base.plugin.mcnews.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一组关键词模板规则：<strong>排除优先</strong>，收录规则之间是「或」关系。
 *
 * <ul>
 *   <li>命中任一排除规则 → 丢弃（即使同时命中收录规则）。</li>
 *   <li>未命中任何排除规则，且没有任何收录规则 → 保留（只写排除规则即为「只筛掉不要的」）。</li>
 *   <li>存在收录规则时，必须命中至少一条才保留。</li>
 * </ul>
 */
public final class NewsKeywordFilter {
    private static final NewsKeywordFilter EMPTY = new NewsKeywordFilter(List.of());

    private final List<NewsKeywordRule> rules;
    private final List<NewsKeywordRule> includes;
    private final List<NewsKeywordRule> excludes;

    private NewsKeywordFilter(List<NewsKeywordRule> rules) {
        this.rules = List.copyOf(rules);
        this.includes = this.rules.stream().filter(rule -> !rule.exclude()).toList();
        this.excludes = this.rules.stream().filter(NewsKeywordRule::exclude).toList();
    }

    /** 严格编译：任一条模板非法即抛出（保存新闻源时使用，错误直接反馈给管理员）。 */
    public static NewsKeywordFilter of(List<String> patterns) {
        return compile(patterns, null);
    }

    /** 宽松编译：非法模板跳过并记入 invalid（运行时使用，一条写错不影响整个源）。 */
    public static NewsKeywordFilter lenient(List<String> patterns) {
        return compile(patterns, new ArrayList<>());
    }

    public static NewsKeywordFilter lenient(List<String> patterns, List<String> invalid) {
        return compile(patterns, invalid == null ? new ArrayList<>() : invalid);
    }

    private static NewsKeywordFilter compile(List<String> patterns, List<String> invalid) {
        if (patterns == null || patterns.isEmpty()) {
            return EMPTY;
        }
        List<NewsKeywordRule> rules = new ArrayList<>();
        for (String raw : patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                rules.add(NewsKeywordRule.parse(raw));
            }
            catch (IllegalArgumentException e) {
                if (invalid == null) {
                    throw e;
                }
                invalid.add(raw.trim() + "：" + e.getMessage());
            }
        }
        return rules.isEmpty() ? EMPTY : new NewsKeywordFilter(rules);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public List<NewsKeywordRule> rules() {
        return rules;
    }

    /** 该条目是否收录。 */
    public boolean accepts(NewsArticle article) {
        return decide(article).keep();
    }

    public Decision decide(NewsArticle article) {
        for (NewsKeywordRule rule : excludes) {
            if (rule.matches(article)) {
                return new Decision(false, true);
            }
        }
        if (includes.isEmpty()) {
            return new Decision(true, false);
        }
        for (NewsKeywordRule rule : includes) {
            if (rule.matches(article)) {
                return new Decision(true, false);
            }
        }
        return new Decision(false, false);
    }

    /** 过滤并按原序返回收录条目；无规则时原样返回。 */
    public List<NewsArticle> filter(List<NewsArticle> items) {
        if (items == null || items.isEmpty() || rules.isEmpty()) {
            return items == null ? List.of() : items;
        }
        List<NewsArticle> kept = new ArrayList<>();
        for (NewsArticle item : items) {
            if (accepts(item)) {
                kept.add(item);
            }
        }
        return kept;
    }

    /**
     * 逐条模板命中统计，供管理端「测试抓取」诊断关键词模板：
     * 命中数按单条规则独立计算（不受排除优先影响），便于发现「写了但一条都没命中」的模板。
     */
    public Stats stats(List<NewsArticle> items) {
        List<NewsArticle> source = items == null ? List.of() : items;
        List<RuleStat> ruleStats = new ArrayList<>();
        for (NewsKeywordRule rule : rules) {
            int hits = 0;
            for (NewsArticle item : source) {
                if (rule.matches(item)) {
                    hits++;
                }
            }
            ruleStats.add(new RuleStat(rule.raw(), rule.kind().label(), rule.field().label(),
                    rule.exclude(), hits));
        }
        int kept = 0;
        int excluded = 0;
        int rejected = 0;
        for (NewsArticle item : source) {
            Decision decision = decide(item);
            if (decision.keep()) {
                kept++;
            }
            else if (decision.excluded()) {
                excluded++;
            }
            else {
                rejected++;
            }
        }
        return new Stats(source.size(), kept, excluded, rejected, ruleStats);
    }

    /** 单条候选的判定结果。 */
    public record Decision(boolean keep, boolean excluded) {
    }

    /** 单条模板的命中情况。 */
    public record RuleStat(String rule, String kind, String field, boolean exclude, int hits) {
    }

    /** 一次过滤的整体统计：total 源条数、kept 收录、excluded 被排除规则丢弃、rejected 未命中收录规则。 */
    public record Stats(int total, int kept, int excluded, int rejected, List<RuleStat> rules) {
    }
}
