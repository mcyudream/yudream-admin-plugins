package online.yudream.base.plugin.minecraft.api;

public record PluginMinecraftServer(String id, String name, String descriptionMarkdown, boolean enabled,
                                    String currentSeasonId, String currentSeasonName, Long currentSeasonStartedAt,
                                    long createdAt, long updatedAt) {
}
