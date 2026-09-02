package online.yudream.base.plugin.mcwiki.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McRecipe;
import org.junit.jupiter.api.Test;

/** recipesProducing / recipesUsing 定点查询：等值匹配 + 版本内存过滤，替代整表扫描。 */
class WikiResourceRepositoryPointQueryTest {

    private static McRecipe recipe(String version, String id, String resultId, List<String> ingredients) {
        return new McRecipe(id, version, "minecraft:crafting_shapeless", resultId, 1, ingredients, List.of(), "{}");
    }

    @Test
    void producingMatchesResultIdAcrossVersionsAndPages() {
        WikiResourceRepository repository = new WikiResourceRepository(new FakeDocumentStore());
        // 同一 resultId 跨两个版本、总数超过单页上限 200，验证翻页与版本过滤都不丢数据
        for (int i = 0; i < 450; i++) {
            repository.saveRecipe(recipe("1.20.6", "planks_" + i, "minecraft:oak_planks", List.of("minecraft:oak_log")));
            repository.saveRecipe(recipe("1.20.4", "planks_" + i, "minecraft:oak_planks", List.of("minecraft:oak_log")));
        }
        repository.saveRecipe(recipe("1.20.6", "stick", "minecraft:stick", List.of("minecraft:oak_planks")));
        List<McRecipe> producing = repository.recipesProducing("1.20.6", "minecraft:oak_planks");
        assertEquals(450, producing.size());
        assertTrue(producing.stream().allMatch(recipe -> "1.20.6".equals(recipe.version())));
    }

    @Test
    void usingMatchesPlainIdAndTagFormWithDedup() {
        WikiResourceRepository repository = new WikiResourceRepository(new FakeDocumentStore());
        repository.saveRecipe(recipe("1.20.6", "stick_from_planks", "minecraft:stick", List.of("minecraft:oak_planks")));
        repository.saveRecipe(recipe("1.20.6", "fence_from_tag", "minecraft:oak_fence", List.of("#minecraft:oak_planks", "minecraft:stick")));
        // 同一配方同时含两种形态时只能出现一次
        repository.saveRecipe(recipe("1.20.6", "both_forms", "minecraft:barrel", List.of("minecraft:oak_planks", "#minecraft:oak_planks")));
        repository.saveRecipe(recipe("1.20.6", "unrelated", "minecraft:torch", List.of("minecraft:coal")));
        repository.saveRecipe(recipe("1.20.4", "other_version", "minecraft:stick", List.of("minecraft:oak_planks")));
        List<McRecipe> using = repository.recipesUsing("1.20.6", "minecraft:oak_planks");
        assertEquals(3, using.size());
        assertTrue(using.stream().anyMatch(recipe -> "stick_from_planks".equals(recipe.id())));
        assertTrue(using.stream().anyMatch(recipe -> "fence_from_tag".equals(recipe.id())));
        assertTrue(using.stream().anyMatch(recipe -> "both_forms".equals(recipe.id())));
        assertTrue(using.stream().noneMatch(recipe -> "unrelated".equals(recipe.id())));
        assertTrue(using.stream().allMatch(recipe -> "1.20.6".equals(recipe.version())));
    }

    /** 文档 "id" 字段会被宿主覆写为存储键 version:recipeId，删除必须直接用它，不能再次拼接前缀。 */
    @Test
    void deleteVersionDataRemovesRecipesOfOnlyThatVersion() {
        WikiResourceRepository repository = new WikiResourceRepository(new FakeDocumentStore());
        repository.saveRecipe(recipe("1.20.6", "stick_from_planks", "minecraft:stick", List.of("minecraft:oak_planks")));
        repository.saveRecipe(recipe("1.20.6", "fence_from_tag", "minecraft:oak_fence", List.of("minecraft:oak_planks")));
        repository.saveRecipe(recipe("1.20.4", "other_version", "minecraft:stick", List.of("minecraft:oak_planks")));
        assertEquals(2, repository.deleteVersionData("1.20.6").get("recipes"));
        assertEquals(0, repository.recipesProducing("1.20.6", "minecraft:stick").size());
        assertEquals(1, repository.recipesProducing("1.20.4", "minecraft:stick").size());
    }
}
