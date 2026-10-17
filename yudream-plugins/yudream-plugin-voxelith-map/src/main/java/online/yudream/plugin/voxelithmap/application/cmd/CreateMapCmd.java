package online.yudream.plugin.voxelithmap.application.cmd;

import online.yudream.plugin.voxelithmap.domain.value.ModJarRef;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;

import java.util.List;

/**
 * 创建地图项目的入参（来自管理端请求体，宿主 Jackson 口径）。
 *
 * @param name            展示名；为空时取存档名
 * @param worldFileId     平台文件 id（存档 zip）
 * @param worldName       存档名（可空，边车识别后会回填）
 * @param clientJarFileId 平台文件 id（原版 client jar）；渲染必需
 * @param clientJarName   客户端 jar 的原始文件名（可空）；引擎按它判定采集版本，建议带上
 * @param modsPackFileId  模组包 zip 的平台文件 id（可空）；渲染前解包成 jar，整包只占一个存储对象
 * @param modsPackName    模组包原始文件名（展示用）
 * @param modJars         模组 jar 列表（可空 / 空 = 纯原版）；按顺序叠在 client jar 之上
 * @param dimension       维度 id
 * @param settings        渲染参数；为空用默认
 * @param allDimensions    true = 按存档包内的目录一次建出全部维度（world / world_nether /
 *                         world_the_end 并列时各建一张，共享同一份存档与同一个世界分组）；
 *                         false / 省略 = 只按 {@code dimension} 建一张
 */
public record CreateMapCmd(
        String name,
        String worldFileId,
        String worldName,
        String clientJarFileId,
        String clientJarName,
        String modsPackFileId,
        String modsPackName,
        List<ModJarRef> modJars,
        String dimension,
        RenderSettings settings,
        Boolean allDimensions) {
}
