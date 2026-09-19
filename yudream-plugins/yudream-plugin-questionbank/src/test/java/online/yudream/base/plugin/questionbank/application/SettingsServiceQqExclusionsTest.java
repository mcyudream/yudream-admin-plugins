package online.yudream.base.plugin.questionbank.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import online.yudream.base.plugin.questionbank.infrastructure.FakeDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 群抽题全局排除项（分类 + 题型）的读写：默认空、去重去空白、题型只留合法枚举名、null 保持、空列表清空。 */
class SettingsServiceQqExclusionsTest {

    private SettingsService settings;

    @BeforeEach
    void setUp() {
        settings = new SettingsService(new FakeDocumentStore());
    }

    @Test
    void defaultIsEmpty() {
        assertEquals(List.of(), settings.qqExcludeCategoryIds());
        assertEquals(List.of(), settings.qqExcludeTypes());
        assertEquals(List.of(), settings.settingsView().get("qqExcludeCategoryIds"));
        assertEquals(List.of(), settings.settingsView().get("qqExcludeTypes"));
    }

    @Test
    void savesTrimmedDeduplicatedValuesAndDropsUnknownTypes() {
        settings.update(null, null, null, null, null,
                new SettingsService.QqExclusions(
                        Arrays.asList(" cat-a ", "cat-b", "cat-a", "   ", null),
                        Arrays.asList("short", " SHORT ", "MULTIPLE", "NOPE", "")),
                null, null, null, null);
        assertEquals(List.of("cat-a", "cat-b"), settings.qqExcludeCategoryIds());
        assertEquals(List.of("SHORT", "MULTIPLE"), settings.qqExcludeTypes());
        assertEquals(List.of("cat-a", "cat-b"), settings.settingsView().get("qqExcludeCategoryIds"));
        assertEquals(List.of("SHORT", "MULTIPLE"), settings.settingsView().get("qqExcludeTypes"));
    }

    @Test
    void nullKeepsEachDimensionSeparatelyAndEmptyListClearsIt() {
        settings.update(null, null, null, null, null,
                new SettingsService.QqExclusions(List.of("cat-a"), List.of("SHORT")), null, null, null, null);
        settings.update(null, null, null, null, null,
                new SettingsService.QqExclusions(null, List.of()), null, null, null, null);
        assertEquals(List.of("cat-a"), settings.qqExcludeCategoryIds());
        assertEquals(List.of(), settings.qqExcludeTypes());
    }
}
