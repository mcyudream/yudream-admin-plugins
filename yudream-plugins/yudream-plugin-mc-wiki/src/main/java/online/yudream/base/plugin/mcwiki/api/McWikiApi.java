package online.yudream.base.plugin.mcwiki.api;

import java.util.List;
import java.util.Optional;

public interface McWikiApi {
    /** 当前对外公开的资源版本；未发布时为空。消费方应优先使用该版本读取物品/配方/贴图。 */
    Optional<String> publishedVersion();
    /** 全部已发布版本（按发布先后排序）；供消费方提供版本选择器 options，不要让管理员手输版本号。 */
    List<String> publishedVersions();
    List<McVersionInfo> listVersions(boolean releaseOnly);
    Optional<McItemEntry> getItem(String version, String namespacedId);
    Page<McItemEntry> searchItems(String version, String keyword, int page, int size);
    /** 指定版本的全量物品摘要（provider 内存索引供给）；供消费方一次性构建本地目录，避免逐页搬运。 */
    List<McItemEntry> items(String version);
    /** 指定版本的全量配方摘要（含九宫格 grid，rawJson 原文为空串）；供消费方一次性构建本地目录。 */
    List<McRecipe> recipes(String version);
    List<McRecipe> getRecipesProducing(String version, String itemId);
    List<McRecipe> getRecipesUsing(String version, String itemId);
    Optional<McMobEntry> getMob(String version, String entityId);
    Optional<byte[]> getTexture(String version, String kind, String path);
    String getTextureCdnKey(String version, String kind, String path);

    record Page<T>(List<T> records, long total, int page, int size) {}
    record McVersionInfo(String id, String type, String releaseTime, String url, boolean latest) {}
    record McItemEntry(String version, String namespacedId, String kind, String nameEn, String nameZh, List<String> tags, String textureKey, String diagnostic) {}
    /** @param grid 预计算的 3x3 配方九宫格（行优先、null 为空格，tag/备选原料已解析为具体物品）；旧数据未重新导入时为空列表 */
    record McRecipe(String id, String version, String type, String resultId, int resultCount, List<String> ingredients, List<String> grid, String rawJson) {}
    record McMobEntry(String version, String entityId, String nameEn, String nameZh, List<String> drops, Integer health, Integer attack, String behavior) {}
}
