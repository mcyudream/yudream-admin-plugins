package online.yudream.base.plugin.skin.api;

public record PluginSkinProfile(String uuid, String name, String ownerId, PluginSkinTexture skin,
                                PluginSkinTexture cape, Long lastModified) {
}
