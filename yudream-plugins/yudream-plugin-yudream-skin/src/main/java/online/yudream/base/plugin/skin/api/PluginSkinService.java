package online.yudream.base.plugin.skin.api;

import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.util.List;
import java.util.Optional;

/** Stable service contract exposed by the yudream-skin plugin. */
public interface PluginSkinService {
    List<PluginSkinProfile> findProfilesByOwner(String ownerId);
    Optional<PluginSkinProfile> findProfileByName(String name);
    Optional<PluginSkinProfile> findProfileByUuid(String uuid);
    List<PluginSkinProfile> findProfilesByNames(List<String> names);
    /** 指定用户的全部衣柜条目（只读视图，按收藏时间升序之外的顺序不作保证）。 */
    List<PluginSkinClosetItem> findClosetByOwner(String ownerId);
    /**
     * 上传皮肤 PNG（base64）到指定用户的皮肤库并加入衣柜，返回新衣柜条目。
     * 校验失败（非 PNG、base64 非法等）抛出 {@link IllegalArgumentException}。
     */
    PluginSkinClosetItem uploadClosetSkin(String ownerId, String name, String model, String base64);
    Optional<PluginSkinTexture> findTextureByHash(String hash);
    Optional<PluginStoredFile> readTexture(String hash);
    void setProfileTexture(String uuid, String textureType, String textureHash);
    void clearProfileTexture(String uuid, String textureType);
}
