package online.yudream.base.plugin.projectprogress.interfaces.res;

import java.util.List;

/**
 * 项目可选的 Minecraft 服务器。
 *
 * <p>{@code subServers} 是该服务器已知的下游子服。单机服为空列表，项目表单据此决定要不要显示
 * 子服选择。
 */
public record ProjectMinecraftServerOptionRes(
        String id,
        String name,
        boolean enabled,
        String currentSeasonId,
        String currentSeasonName,
        List<ProjectMinecraftSubServerRes> subServers
) {

    public ProjectMinecraftServerOptionRes {
        subServers = subServers == null ? List.of() : List.copyOf(subServers);
    }

    /** 一台下游子服；单机服为空列表。 */
    public record ProjectMinecraftSubServerRes(String name, String address, int online, boolean sensor,
                                               boolean defaultServer, int sort) {
    }
}
