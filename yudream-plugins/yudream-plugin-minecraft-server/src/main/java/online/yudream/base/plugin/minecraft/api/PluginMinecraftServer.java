package online.yudream.base.plugin.minecraft.api;

import java.util.List;

/**
 * 一台 Minecraft 服务器条目。
 *
 * <p>{@code subServers} 是代理端桥接上报的下游子服列表（见 {@link PluginMinecraftSubServer}）。
 * 单机服没有子服维度，该列表为空；消费方据此决定要不要给操作者显示子服选择。
 */
public record PluginMinecraftServer(String id, String name, String descriptionMarkdown, boolean enabled,
                                    String currentSeasonId, String currentSeasonName, Long currentSeasonStartedAt,
                                    long createdAt, long updatedAt,
                                    List<PluginMinecraftSubServer> subServers) {

    public PluginMinecraftServer {
        subServers = subServers == null ? List.of() : List.copyOf(subServers);
    }

    /** 没有子服维度时的构造（单机服，或调用方不关心子服列表）。 */
    public PluginMinecraftServer(String id, String name, String descriptionMarkdown, boolean enabled,
                                 String currentSeasonId, String currentSeasonName, Long currentSeasonStartedAt,
                                 long createdAt, long updatedAt) {
        this(id, name, descriptionMarkdown, enabled, currentSeasonId, currentSeasonName, currentSeasonStartedAt,
                createdAt, updatedAt, List.of());
    }
}
