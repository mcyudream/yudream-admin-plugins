package online.yudream.base.plugin.mcguess.infrastructure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import online.yudream.base.plugin.mcwiki.api.McWikiApi;

/** McWikiApi 测试桩：可变的发布版本与全量清单，记录批量方法调用次数。 */
public class StubMcWikiApi implements McWikiApi {
    public String published;
    /** 已发布版本列表；null 时按 published 单版本推导。 */
    public List<String> publishedList;
    public List<McItemEntry> itemList = List.of();
    public List<McRecipe> recipeList = List.of();
    public int itemsCalls;
    public int recipesCalls;
    public final Map<String, McItemEntry> byId = new HashMap<>();
    public final Map<String, byte[]> textures = new HashMap<>();
    /** 共享渲染资产桩，键为 namespacedId；模拟一键更新渲染资产后的覆盖集合。 */
    public final Map<String, byte[]> renders = new HashMap<>();

    public Optional<String> publishedVersion() { return Optional.ofNullable(published); }
    public List<String> publishedVersions() { return publishedList != null ? publishedList : (published == null ? List.of() : List.of(published)); }
    public List<McVersionInfo> listVersions(boolean releaseOnly) { return List.of(); }
    public Optional<McItemEntry> getItem(String version, String namespacedId) {
        McItemEntry versioned = byId.get(version + "|" + namespacedId);
        return Optional.ofNullable(versioned != null ? versioned : byId.get(namespacedId));
    }
    public Page<McItemEntry> searchItems(String version, String keyword, int page, int size) { return new Page<>(List.of(), 0, page, size); }
    public List<McItemEntry> items(String version) { itemsCalls++; return itemList; }
    public List<McRecipe> recipes(String version) { recipesCalls++; return recipeList; }
    public List<McRecipe> getRecipesProducing(String version, String itemId) { return List.of(); }
    public List<McRecipe> getRecipesUsing(String version, String itemId) { return List.of(); }
    public Optional<McMobEntry> getMob(String version, String entityId) { return Optional.empty(); }
    public Optional<byte[]> getTexture(String version, String kind, String path) { return Optional.ofNullable(textures.get(kind + "/" + path)); }
    public String getTextureCdnKey(String version, String kind, String path) { return kind + "/" + path; }
    public Optional<byte[]> getRender(String namespacedId, String kind) { return Optional.ofNullable(renders.get(namespacedId)); }
    public boolean hasRender(String namespacedId, String kind) { return renders.containsKey(namespacedId); }
}
