package online.yudream.base.plugin.skin.api;

/**
 * Stable read-only view of a yudream-skin closet item.
 *
 * @param id         stable item id ({userId}:{textureHash})
 * @param textureHash texture hash usable with the anonymous texture endpoint
 * @param itemName   display name chosen by the owner
 * @param createdAt  epoch millis
 */
public record PluginSkinClosetItem(
        String id,
        String textureHash,
        String itemName,
        Long createdAt
) {
}
