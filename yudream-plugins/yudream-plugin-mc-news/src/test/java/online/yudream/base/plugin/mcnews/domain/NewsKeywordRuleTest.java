package online.yudream.base.plugin.mcnews.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 关键词模板：子串 / 正则 / 通配 / 字段限定 / 排除，以及非法模板的拒绝与宽松降级。 */
class NewsKeywordRuleTest {

    private static NewsArticle article(String title, String summary) {
        return article(title, summary, "https://www.minecraft.net/zh-hans/article/mc-26-3-rc2");
    }

    private static NewsArticle article(String title, String summary, String url) {
        return new NewsArticle("id", "src", "测试源", title, summary, "", "", url, "", 0, 0, 0, "");
    }

    @Test
    void plainKeywordIsCaseInsensitiveSubstring() {
        NewsKeywordRule rule = NewsKeywordRule.parse("a minecraft java");
        assertEquals(NewsKeywordRule.Kind.TEXT, rule.kind());
        assertEquals(NewsKeywordRule.Field.ANY, rule.field());
        assertTrue(rule.matches(article("A Minecraft Java Release Candidate", "")));
        assertTrue(rule.matches(article("无标题命中", "A Minecraft Java 版更新")));
        assertFalse(rule.matches(article("Minecraft Bedrock 更新", "基岩版")));
    }

    @Test
    void textKindEscapesRegexMetacharacters() {
        NewsKeywordRule rule = NewsKeywordRule.parse("1.21.*");
        assertEquals(NewsKeywordRule.Kind.TEXT, rule.kind());
        assertTrue(rule.matches(article("版本 1.21.* 说明", "")));
        assertFalse(rule.matches(article("版本 1.21.4 说明", "")), "子串模式必须转义正则元字符");
    }

    @Test
    void regexPrefixIsExplicitAndAnchorsWork() {
        NewsKeywordRule rule = NewsKeywordRule.parse("re:^Version\\s+\\d+");
        assertEquals(NewsKeywordRule.Kind.REGEX, rule.kind());
        assertTrue(rule.matches(article("Version 1.21.4", "")));
        assertFalse(rule.matches(article("旧 Version 1.21.4", "")));
    }

    @Test
    void slashFormRegexCarriesFlags() {
        NewsKeywordRule rule = NewsKeywordRule.parse("/^release candidate \\d+/i");
        assertEquals(NewsKeywordRule.Kind.REGEX, rule.kind());
        assertTrue(rule.matches(article("Release Candidate 2", "")));
        NewsKeywordRule multiline = NewsKeywordRule.parse("/^Beta$/m");
        assertEquals(NewsKeywordRule.Kind.REGEX, multiline.kind());
        assertTrue(multiline.matches(article("首个标题行", "Beta")), "m 标志应让 ^ $ 匹配行边界");
    }

    @Test
    void allRulesAreCaseInsensitiveByDefault() {
        assertTrue(NewsKeywordRule.parse("re:^snapshot").matches(article("SNAPSHOT 26w03a", "")));
        assertTrue(NewsKeywordRule.parse("/preview/").matches(article("PREVIEW 版", "")));
    }

    @Test
    void globMatchesAnywhereWithStarAndQuestionMark() {
        NewsKeywordRule rule = NewsKeywordRule.parse("glob:*Snapshot*");
        assertEquals(NewsKeywordRule.Kind.GLOB, rule.kind());
        assertTrue(rule.matches(article("Minecraft 26.1 Snapshot 3", "")));
        assertTrue(NewsKeywordRule.parse("glob:24w?3a").matches(article("24w03a 快照", "")));
        assertFalse(rule.matches(article("Minecraft 26.1 Release 3", "")));
        assertFalse(NewsKeywordRule.parse("glob:Snapshot*跨行").matches(article("Snapshot", "跨行")),
                "通配 * 不跨行（标题与摘要之间不会被通配串起来）");
    }

    @Test
    void fieldScopeLimitsHaystack() {
        NewsArticle item = article("Release", "redstone 修复", "https://example.com/zh-hans/article/foo");
        assertFalse(NewsKeywordRule.parse("title:redstone").matches(item));
        assertTrue(NewsKeywordRule.parse("summary:redstone").matches(item));
        assertTrue(NewsKeywordRule.parse("title:release").matches(item));
        assertTrue(NewsKeywordRule.parse("url:article/foo").matches(item));
        assertFalse(NewsKeywordRule.parse("url:redstone").matches(item));
    }

    @Test
    void regexNeedsExplicitKindEvenWithFieldPrefix() {
        NewsArticle item = article("Version 1.21.4", "");
        assertFalse(NewsKeywordRule.parse("title:^Version").matches(item), "缺省类型按字面量处理，^ 不是锚点");
        assertTrue(NewsKeywordRule.parse("title:re:^Version").matches(item));
        assertTrue(NewsKeywordRule.parse("re:^Version").matches(item));
    }

    @Test
    void excludePrefixAndFullWidthBang() {
        NewsKeywordRule rule = NewsKeywordRule.parse("!re:redstone");
        assertTrue(rule.exclude());
        assertEquals(NewsKeywordRule.Kind.REGEX, rule.kind());
        assertTrue(NewsKeywordRule.parse("！glob:*Beta*").exclude(), "全角感叹号同样表示排除");
    }

    @Test
    void unknownPrefixFallsBackToLiteral() {
        NewsKeywordRule rule = NewsKeywordRule.parse("http://example.com/a");
        assertEquals(NewsKeywordRule.Kind.TEXT, rule.kind());
        assertEquals(NewsKeywordRule.Field.ANY, rule.field());
        assertTrue(rule.matches(article("", "详见 http://example.com/a")));
    }

    @Test
    void invalidRegexIsRejectedWithReason() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> NewsKeywordRule.parse("re:(unclosed"));
        assertTrue(error.getMessage().contains("正则表达式不合法"), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> NewsKeywordRule.parse("re:"));
        assertThrows(IllegalArgumentException.class, () -> NewsKeywordRule.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> NewsKeywordRule.parse("!"));
    }

    @Test
    void overlongPatternIsRejected() {
        String pattern = "a".repeat(NewsKeywordRule.MAX_PATTERN_LENGTH + 1);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> NewsKeywordRule.parse(pattern));
        assertTrue(error.getMessage().contains("过长"), error.getMessage());
    }

    @Test
    void onlyExcludeRulesKeepsEverythingElse() {
        NewsKeywordFilter filter = NewsKeywordFilter.of(List.of("!re:beta"));
        assertEquals(1, filter.rules().size());
        assertTrue(filter.accepts(article("26.3 Release", "")));
        assertFalse(filter.accepts(article("26.3 Beta", "")));
    }

    @Test
    void includeRulesAreOrCombinedAndExcludeWins() {
        NewsKeywordFilter filter = NewsKeywordFilter.of(List.of("glob:*Java*", "re:^Beta", "!re:marketplace"));
        assertTrue(filter.accepts(article("A Minecraft Java Release", "")));
        assertTrue(filter.accepts(article("Beta 1.21", "")));
        assertFalse(filter.accepts(article("Bedrock 更新", "")), "未命中任何收录模板");
        assertFalse(filter.accepts(article("Marketplace Java 上新", "")), "排除模板优先于收录模板");
    }

    @Test
    void emptyRulesKeepEverything() {
        NewsKeywordFilter filter = NewsKeywordFilter.of(List.of());
        assertTrue(filter.isEmpty());
        List<NewsArticle> items = List.of(article("任意", ""));
        assertEquals(items, filter.filter(items));
        NewsKeywordFilter.Stats stats = filter.stats(items);
        assertEquals(1, stats.total());
        assertEquals(1, stats.kept());
        assertEquals(0, stats.excluded());
        assertEquals(0, stats.rejected());
        assertTrue(stats.rules().isEmpty());
        assertFalse(filter.decide(article("任意", "")).excluded());
    }

    @Test
    void lenientSkipsInvalidRulesAndReportsThem() {
        List<String> invalid = new java.util.ArrayList<>();
        NewsKeywordFilter filter = NewsKeywordFilter.lenient(List.of("re:(unclosed", "java"), invalid);
        assertEquals(1, filter.rules().size());
        assertEquals(1, invalid.size());
        assertTrue(invalid.get(0).contains("正则表达式不合法"), invalid.get(0));
        assertTrue(filter.accepts(article("A Minecraft Java Release", "")));
    }

    @Test
    void strictFilterRejectsInvalidRules() {
        assertThrows(IllegalArgumentException.class, () -> NewsKeywordFilter.of(List.of("re:(unclosed")));
    }

    @Test
    void statsReportPerRuleHitsAndOutcomes() {
        NewsKeywordFilter filter = NewsKeywordFilter.of(List.of("glob:*Java*", "title:re:^Beta", "!re:marketplace"));
        List<NewsArticle> items = List.of(
                article("A Minecraft Java Release", ""),
                article("Beta 26.3", ""),
                article("Marketplace Java 上新", ""),
                article("Bedrock 更新", ""));
        NewsKeywordFilter.Stats stats = filter.stats(items);

        assertEquals(4, stats.total());
        assertEquals(2, stats.kept());
        assertEquals(1, stats.excluded());
        assertEquals(1, stats.rejected());
        assertEquals(3, stats.rules().size());
        NewsKeywordFilter.RuleStat java = stats.rules().get(0);
        assertEquals("glob:*Java*", java.rule());
        assertEquals(2, java.hits(), "命中数按单条模板独立统计，不受排除优先影响");
        assertEquals("通配", java.kind());
        assertEquals("标题 + 摘要", java.field());
        assertFalse(java.exclude());
        assertEquals(1, stats.rules().get(1).hits());
        assertEquals(1, stats.rules().get(2).hits());
        assertTrue(stats.rules().get(2).exclude());
    }

    @Test
    void globKeepsLiteralPartsIntact() {
        assertTrue(NewsKeywordRule.parse("glob:a*b?c").matches(article("aZZbXc", "")));
        assertTrue(NewsKeywordRule.parse("glob:a.b").matches(article("a.b", "")));
        assertFalse(NewsKeywordRule.parse("glob:a.b").matches(article("axb", "")), "glob 中的点号按字面处理");
    }
}
