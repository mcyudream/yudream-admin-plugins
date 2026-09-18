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
    /**
     * 上传披风 PNG（base64）到指定用户的皮肤库并加入衣柜，返回新衣柜条目。
     * 校验失败（非 PNG、base64 非法等）抛出 {@link IllegalArgumentException}。
     */
    PluginSkinClosetItem uploadClosetCape(String ownerId, String name, String base64);
    /**
     * 为指定用户创建 Minecraft 角色。角色名重复或超出上限时抛出
     * {@link IllegalArgumentException}。首个角色自动成为默认角色。
     */
    PluginSkinProfile createPlayerForOwner(String ownerId, String name);
    Optional<PluginSkinTexture> findTextureByHash(String hash);
    Optional<PluginStoredFile> readTexture(String hash);
    void setProfileTexture(String uuid, String textureType, String textureHash);
    void clearProfileTexture(String uuid, String textureType);
    /**
     * 删除指定用户自己的一个衣柜条目。条目不存在或不属于该用户时抛出
     * {@link IllegalArgumentException}（YAP §6.11 删除端点的落点）。
     */
    void removeClosetSkin(String ownerId, String itemId);
    /**
     * 公共皮肤库（站点公开纹理 + 用户自己上传的私有纹理），page 从 1 起。
     * YAP §6.11 library 端点的数据源。
     */
    List<PluginSkinTexture> findVisibleTextures(String ownerId, int page, int size);
    /**
     * 把公共纹理或自己上传的纹理收进衣柜（幂等：同纹理同用户为同一条目）。
     * 纹理不存在，或非公开且非自己上传时抛出 {@link IllegalArgumentException}。
     */
    PluginSkinClosetItem collectTextureToCloset(String ownerId, String textureHash, String itemName);
}
