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
    Optional<PluginSkinTexture> findTextureByHash(String hash);
    Optional<PluginStoredFile> readTexture(String hash);
    void setProfileTexture(String uuid, String textureType, String textureHash);
    void clearProfileTexture(String uuid, String textureType);
}
