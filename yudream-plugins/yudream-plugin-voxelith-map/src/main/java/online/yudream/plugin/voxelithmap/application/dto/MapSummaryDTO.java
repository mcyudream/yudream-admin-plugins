package online.yudream.plugin.voxelithmap.application.dto;

import java.util.List;

/**
 * 公开地图列表项（匿名可见）。
 *
 * <p>刻意只暴露查看器需要的东西：名称、维度、包围盒、出生点、瓦片规模与当前代 id。
 * 存档文件名、平台文件 id、错误信息这些属于管理端，不进公开 DTO。</p>
 *
 * @param id            地图 id
 * @param name          展示名
 * @param dimension     维度 id
 * @param ready         是否有可浏览的产物
 * @param version       内容版本（全部瓦片 sha1 的聚合哈希）
 * @param tileCount     瓦片总数
 * @param boundsMin     包围盒最小角 [x,y,z]
 * @param boundsMax     包围盒最大角 [x,y,z]
 * @param center        包围盒中心 [x,y,z]（前端初始机位）
 * @param spawn         出生点 [x,y,z]
 * @param generatedAt   产物生成时间
 */
public record MapSummaryDTO(
        String id,
        String name,
        String dimension,
        boolean ready,
        String version,
        int tileCount,
        List<Double> boundsMin,
        List<Double> boundsMax,
        List<Double> center,
        List<Double> spawn,
        String generatedAt) {
}
