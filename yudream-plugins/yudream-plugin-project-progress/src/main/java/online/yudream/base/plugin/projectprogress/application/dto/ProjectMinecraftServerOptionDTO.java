package online.yudream.base.plugin.projectprogress.application.dto;

import java.util.List;

/**
 * 项目可选的 Minecraft 服务器。
 *
 * <p>{@code subServers} 是该服务器已知的下游子服（来自代理端桥接上报的拓扑）。单机服为空列表，
 * 项目表单据此决定要不要显示子服选择。
 */
public record ProjectMinecraftServerOptionDTO(
        String id,
        String name,
        boolean enabled,
        String currentSeasonId,
        String currentSeasonName,
        List<ProjectMinecraftSubServerDTO> subServers
) {

    public ProjectMinecraftServerOptionDTO {
        subServers = subServers == null ? List.of() : List.copyOf(subServers);
    }

    /** 一台下游子服；单机服为空列表。 */
    public record ProjectMinecraftSubServerDTO(String name, String address, int online, boolean sensor,
                                               boolean defaultServer, int sort) {
    }
}
