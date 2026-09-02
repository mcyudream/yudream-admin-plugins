package online.yudream.base.plugin.mcguess.infrastructure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import online.yudream.base.plugin.mcguess.domain.McCatalog;
import online.yudream.base.plugin.mcguess.domain.McItem;
import online.yudream.base.plugin.mcguess.domain.McRecipe;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McItemEntry;

/** 物品与配方目录快照；由 {@link WikiCatalogSource} 懒加载，渲染图字节由 {@link IconSupport} 按需拉取。 */
public record McAssetsSnapshot(String versionId, List<McItemEntry> items, List<McWikiApi.McRecipe> recipes) {
    public static McAssetsSnapshot load(McWikiApi service, String versionId) {
        // 物品与配方各一次全量清单调用（provider 内存索引供给），取代逐物品配方查询的数万次往返
        return new McAssetsSnapshot(versionId, List.copyOf(service.items(versionId)), List.copyOf(service.recipes(versionId)));
    }

    /** 图标可用性走共享渲染资产库的内存名称索引（hasRender），不再依赖导入期写入的 textureKey。 */
    public McCatalog catalog(McWikiApi service) {
        List<McItem> catalogItems = items.stream().map(item -> new McItem(item.namespacedId(), item.namespacedId(), item.nameZh() == null ? item.nameEn() : item.nameZh(), recipes.stream().anyMatch(recipe -> item.namespacedId().equals(recipe.resultId())), service.hasRender(item.namespacedId(), item.kind()))).toList();
        // 领域侧按成品 id 查配方（recipeOf/treeOf），键必须是 resultId 而不是配方 id；
        // 同一成品有多个配方时优先合成台配方（有序 > 无序 > 熔炼等其他类型），同优先级保留先出现的
        Map<String, McRecipe> catalogRecipes = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (McWikiApi.McRecipe recipe : recipes) {
            String resultId = recipe.resultId();
            if (resultId == null || resultId.isBlank()) continue;
            int rank = rankOf(recipe.type());
            Integer existing = ranks.get(resultId);
            if (existing != null && existing >= rank) continue;
            ranks.put(resultId, rank);
            catalogRecipes.put(resultId, new McRecipe(resultId, gridOf(recipe), recipe.resultCount()));
        }
        return new McCatalog(catalogItems, catalogRecipes, versionId);
    }

    private static int rankOf(String type) {
        if (type == null) return 1;
        return switch (type) {
            case "minecraft:crafting_shaped" -> 3;
            case "minecraft:crafting_shapeless" -> 2;
            default -> 1;
        };
    }

    /** 预计算九宫格优先；旧导入数据没有 grid 字段时退化为扁平原料顺序填充（tag 伪 id 猜不到，按空格跳过）。 */
    private static List<String> gridOf(McWikiApi.McRecipe recipe) {
        List<String> grid = recipe.grid();
        if (grid == null || grid.isEmpty()) {
            List<String> cells = new ArrayList<>(Collections.nCopies(9, null));
            List<String> ingredients = recipe.ingredients() == null ? List.of() : recipe.ingredients();
            int slot = 0;
            for (String ingredient : ingredients) {
                if (slot >= 9) break;
                if (ingredient == null || ingredient.startsWith("#")) continue;
                cells.set(slot++, ingredient);
            }
            return Collections.unmodifiableList(cells);
        }
        if (grid.size() >= 9) return grid.subList(0, 9);
        List<String> cells = new ArrayList<>(grid);
        while (cells.size() < 9) cells.add(null);
        return Collections.unmodifiableList(cells);
    }
}
