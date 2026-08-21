package online.yudream.base.plugin.mcguess.domain;

import online.yudream.base.plugin.mcguess.infrastructure.McDataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于真实数据集验证 2.1.0 新增目录能力：全局出现分数、同族物品与带图标物品池。
 */
class McCatalogExtendedTest {

    private static McCatalog catalog;

    @BeforeAll
    static void load() {
        catalog = McDataLoader.load(McCatalogExtendedTest.class.getClassLoader());
    }

    @Test
    void iconPoolCoversItemsWithIcons() {
        List<McItem> pool = catalog.iconItems();
        assertTrue(pool.size() > 1000, "带图标物品池应覆盖绝大部分物品");
        assertTrue(pool.stream().allMatch(McItem::icon), "池内物品必须都有图标");
        assertTrue(pool.stream().anyMatch(item -> item.id().equals("diamond_sword")));
    }

    @Test
    void occurrenceScoreCountsWholeDataset() {
        // 钻石作为原料出现在大量配方树中（钻石剑/斧/镐/盔甲等 + 钻石块相关）
        assertTrue(catalog.occurrenceScore("diamond") > 10, "钻石的全局出现次数应很高");
        // 木棍作为原料出现在大量配方中（工具、武器、火把、铁轨、梯子……）
        assertTrue(catalog.occurrenceScore("stick") > 20, "木棍的全局出现次数应很高");
        // 基岩不是任何配方的原料
        assertEquals(0, catalog.occurrenceScore("bedrock"));
        // 不存在的物品为 0
        assertEquals(0, catalog.occurrenceScore("not_an_item"));
    }

    @Test
    void familyOfGroupsColorVariants() {
        List<McItem> family = catalog.familyOf("red_wool");
        assertTrue(family.size() >= 15, "羊毛同族应包含其余颜色变体");
        assertTrue(family.stream().anyMatch(item -> item.id().equals("blue_wool")));
        assertTrue(family.stream().allMatch(item -> item.id().endsWith("_wool")));
        assertFalse(family.stream().anyMatch(item -> item.id().equals("red_wool")), "同族不含自身");
    }

    @Test
    void familyOfEmptyForUniqueNames() {
        // 归一化后无其他变体的物品没有同族
        assertTrue(catalog.familyOf("diamond_sword").isEmpty());
        // 未知物品同样为空
        assertTrue(catalog.familyOf("not_an_item").isEmpty());
    }
}
