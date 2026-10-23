package online.yudream.base.plugin.ymcl.api;

import java.util.List;
import java.util.Map;

/**
 * MIP 整合包目录对外服务（供其他插件读取 pack × 服务器绑定 的聚合视图，
 * 如 mc-interop 统一出口）。实现由适配器 onEnable 经
 * {@code context.exposeService} 暴露；消费方以 provided 依赖 +
 * {@code context.dependencyAvailable("ymcl-adapter")} 软桥调用。
 *
 * <p>每条目 = 一个整合包最新发布版本 × 一个绑定服务器；未绑定服务器的
 * 版本、已 yank 版本、可选增强包（optionalPackId）不进目录。</p>
 */
public interface YmclPackCatalogService {

    /**
     * 聚合目录条目。键（值均为 String/Long）：
     * packId, packName, version, channel, releasedAt, gameVersion,
     * loaderType, loaderVersion, sha512(整包 mrpack), sizeBytes,
     * mrpackPath(/mip/... 相对路径), serverId, serverName, serverAddress。
     */
    List<Map<String, Object>> catalog();
}
