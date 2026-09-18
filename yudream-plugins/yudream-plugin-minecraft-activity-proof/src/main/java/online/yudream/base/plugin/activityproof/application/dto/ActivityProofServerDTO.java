package online.yudream.base.plugin.activityproof.application.dto;

import java.util.List;

/**
 * 活动核验可选的一台 Minecraft 服务器。
 *
 * <p>{@code subServers} 是该服务器已知的下游子服（来自代理端桥接上报的拓扑）。单机服为空列表，
 * 编辑页据此决定要不要显示子服选择。
 */
public record ActivityProofServerDTO(
        String id,
        String name,
        boolean enabled,
        String currentSeasonName,
        Long currentSeasonStartedAt,
        List<ActivityProofSubServerDTO> subServers
) {

    public ActivityProofServerDTO {
        subServers = subServers == null ? List.of() : List.copyOf(subServers);
    }

    /** 没有子服维度时的构造（单机服）。 */
    public ActivityProofServerDTO(String id, String name, boolean enabled, String currentSeasonName,
                                  Long currentSeasonStartedAt) {
        this(id, name, enabled, currentSeasonName, currentSeasonStartedAt, List.of());
    }
}
