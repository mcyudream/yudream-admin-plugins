package online.yudream.base.plugin.mcnews.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import online.yudream.base.plugin.mcnews.domain.NewsSource;
import online.yudream.base.plugin.mcnews.infrastructure.McNewsStore;
import org.junit.jupiter.api.Test;

/** 新闻源保存时的关键词模板校验：合法形式放行，非法形式在保存阶段即拒绝（HTTP 400）。 */
class NewsSourceServiceTest {

    private final NewsSourceService sources =
            new NewsSourceService(new McNewsStore(new InMemoryDocumentStore()));

    private NewsSource create(List<String> keywords) {
        return sources.create("测试源", NewsSource.TYPE_MCNET, "https://example.com/a.json", keywords, true);
    }

    @Test
    void keepsAllTemplateForms() {
        List<String> keywords = List.of("A Minecraft Java", "/^Release\\s+\\d+/i", "re:^Beta", "glob:*Snapshot*",
                "title:^Version", "!re:redstone");
        assertEquals(keywords, create(keywords).keywords());
    }

    @Test
    void splitsMultilinePasteAndDropsBlankDuplicates() {
        List<String> keywords = new ArrayList<>();
        keywords.add("A Minecraft Java\n\n  A Minecraft Java  \nre:^Beta");
        keywords.add("   ");
        assertEquals(List.of("A Minecraft Java", "re:^Beta"), create(keywords).keywords());
    }

    @Test
    void rejectsInvalidRegexWithTemplateContext() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> create(List.of("re:(unclosed")));
        assertTrue(error.getMessage().contains("关键词模板「re:(unclosed」不合法"), error.getMessage());
        assertTrue(error.getMessage().contains("正则表达式不合法"), error.getMessage());
    }

    @Test
    void rejectsTooManyTemplates() {
        List<String> keywords = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            keywords.add("词" + i);
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> create(keywords));
        assertTrue(error.getMessage().contains("最多 40 条"), error.getMessage());
    }

    @Test
    void updateKeepsExistingTemplatesWhenKeywordsAbsent() {
        NewsSource created = create(List.of("^Beta"));
        NewsSource updated = sources.update(created.id(), null, null, null, null, false);
        assertEquals(List.of("^Beta"), updated.keywords());
        assertFalse(updated.enabled());
    }

    @Test
    void updateValidatesNewTemplates() {
        NewsSource created = create(List.of("^Beta"));
        assertThrows(IllegalArgumentException.class,
                () -> sources.update(created.id(), null, null, null, List.of("re:[unclosed"), null));
    }
}
