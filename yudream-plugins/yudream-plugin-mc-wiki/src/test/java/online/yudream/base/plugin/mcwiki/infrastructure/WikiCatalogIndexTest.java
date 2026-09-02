package online.yudream.base.plugin.mcwiki.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McItemEntry;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McRecipe;
import org.junit.jupiter.api.Test;

/** 按版本目录内存索引：模糊匹配语义与仓储一致、配方摘要剥离 rawJson、失效后重建。 */
class WikiCatalogIndexTest {

    private static McItemEntry item(String version, String id, String nameEn, String nameZh) {
        return new McItemEntry(version, id, "item", nameEn, nameZh, List.of(), null, "");
    }

    @Test
    void searchMatchesIdAndNamesCaseInsensitively() {
        WikiResourceRepository repository = new WikiResourceRepository(new FakeDocumentStore());
        repository.saveItem(item("1.20.6", "minecraft:oak_log", "Oak Log", "橡木原木"));
        repository.saveItem(item("1.20.6", "minecraft:spruce_log", "Spruce Log", "云杉原木"));
        repository.saveItem(item("1.20.6", "minecraft:stick", "Stick", "木棍"));
        WikiCatalogIndex index = new WikiCatalogIndex(repository);
        WikiCatalogIndex.VersionIndex view = index.get("1.20.6");
        assertEquals(3, view.count(null));
        assertEquals(2, view.count("log"));
        assertEquals(2, view.search("原木", 1, 200).size());
        assertEquals(List.of("minecraft:spruce_log"), view.search("SPRUCE", 1, 200).stream().map(McItemEntry::namespacedId).toList());
        // 分页：size 上限 200，第二页取余数
        assertEquals(2, view.search(null, 1, 2).size());
        assertEquals(1, view.search(null, 2, 2).size());
    }

    @Test
    void recipesAreSummariesWithoutRawJsonGroupedByResult() {
        WikiResourceRepository repository = new WikiResourceRepository(new FakeDocumentStore());
        List<String> grid = java.util.Arrays.asList(null, "minecraft:oak_log", null, null, null, null, null, null, null);
        repository.saveRecipe(new McRecipe("oak_planks", "1.20.6", "minecraft:crafting_shapeless", "minecraft:oak_planks", 4, List.of("minecraft:oak_log"), grid, "{\"full\":\"json\"}"));
        repository.saveRecipe(new McRecipe("oak_planks_alt", "1.20.6", "minecraft:crafting_shapeless", "minecraft:oak_planks", 4, List.of("#minecraft:logs"), List.of(), "{}"));
        repository.saveRecipe(new McRecipe("stick", "1.20.6", "minecraft:crafting_shaped", "minecraft:stick", 4, List.of("minecraft:oak_planks"), List.of(), "{}"));
        WikiCatalogIndex.VersionIndex view = new WikiCatalogIndex(repository).get("1.20.6");
        assertEquals(3, view.recipes().size());
        assertTrue(view.recipes().stream().allMatch(recipe -> recipe.rawJson().isEmpty()));
        assertEquals(2, view.recipesByResult().get("minecraft:oak_planks").size());
        // grid 摘要保留九宫格（含 null 空格）
        assertEquals(9, view.recipesByResult().get("minecraft:oak_planks").getFirst().grid().size());
    }

    @Test
    void invalidateRebuildsWithFreshData() {
        FakeDocumentStore store = new FakeDocumentStore();
        WikiResourceRepository repository = new WikiResourceRepository(store);
        repository.saveItem(item("1.20.6", "minecraft:oak_log", "Oak Log", "橡木原木"));
        WikiCatalogIndex index = new WikiCatalogIndex(repository);
        assertEquals(1, index.get("1.20.6").count(null));
        repository.saveItem(item("1.20.6", "minecraft:stick", "Stick", "木棍"));
        // 未失效前仍是旧快照
        assertEquals(1, index.get("1.20.6").count(null));
        index.invalidate("1.20.6");
        assertEquals(2, index.get("1.20.6").count(null));
    }
}
