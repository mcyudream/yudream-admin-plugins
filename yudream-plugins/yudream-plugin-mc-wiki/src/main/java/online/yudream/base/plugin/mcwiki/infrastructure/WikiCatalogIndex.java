package online.yudream.base.plugin.mcwiki.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McItemEntry;
import online.yudream.base.plugin.mcwiki.api.McWikiApi.McRecipe;

/**
 * 按版本的目录内存索引：物品摘要、名称模糊匹配与 resultId→配方映射。
 * PluginDocumentStore 只有等值查询，模糊搜索走存储层必须整集合扫描；
 * 索引在首次请求时经约二十次分页查询构建一次，之后全走内存；
 * 版本数据变化（重新导入 / 删除）后由调用方 {@link #invalidate(String)}，下次访问重建。
 * 配方只保留摘要（含九宫格 grid），不搬 rawJson 全文。
 */
public final class WikiCatalogIndex {
    private final WikiResourceRepository resources;
    private final ConcurrentMap<String, VersionIndex> indexes = new ConcurrentHashMap<>();

    public WikiCatalogIndex(WikiResourceRepository resources) { this.resources = resources; }

    public VersionIndex get(String version) { return indexes.computeIfAbsent(version, this::build); }

    public void invalidate(String version) { if (version != null && !version.isBlank()) indexes.remove(version); }

    private VersionIndex build(String version) {
        List<McItemEntry> items = List.copyOf(resources.allItems(version));
        List<McRecipe> recipes = resources.allRecipes(version).stream()
                .map(recipe -> new McRecipe(recipe.id(), recipe.version(), recipe.type(), recipe.resultId(), recipe.resultCount(), recipe.ingredients(), recipe.grid(), ""))
                .toList();
        Map<String, List<McRecipe>> byResult = new LinkedHashMap<>();
        for (McRecipe recipe : recipes) {
            if (recipe.resultId() == null || recipe.resultId().isBlank()) continue;
            byResult.computeIfAbsent(recipe.resultId(), key -> new ArrayList<>()).add(recipe);
        }
        Map<String, List<McRecipe>> frozen = new LinkedHashMap<>();
        byResult.forEach((resultId, list) -> frozen.put(resultId, List.copyOf(list)));
        return new VersionIndex(version, items, recipes, Map.copyOf(frozen));
    }

    /**
     * 单版本目录视图。名称模糊匹配语义与仓储列表查询一致：
     * namespacedId / nameZh / nameEn 任一字段小写包含关键字即命中，大小写不敏感。
     */
    public record VersionIndex(String version, List<McItemEntry> items, List<McRecipe> recipes,
                               Map<String, List<McRecipe>> recipesByResult) {
        public List<McItemEntry> search(String keyword, int page, int size) {
            int capped = Math.min(Math.max(size, 1), 200);
            return filtered(keyword).skip((long) (Math.max(page, 1) - 1) * capped).limit(capped).toList();
        }

        public long count(String keyword) { return filtered(keyword).count(); }

        private Stream<McItemEntry> filtered(String keyword) {
            if (keyword == null || keyword.isBlank()) return items.stream();
            String q = keyword.toLowerCase(Locale.ROOT);
            return items.stream().filter(item -> contains(item.namespacedId(), q) || contains(item.nameZh(), q) || contains(item.nameEn(), q));
        }

        private static boolean contains(String value, String q) {
            return value != null && value.toLowerCase(Locale.ROOT).contains(q);
        }
    }
}
