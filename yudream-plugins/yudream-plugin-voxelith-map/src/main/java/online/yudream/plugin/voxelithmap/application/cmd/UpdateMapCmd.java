package online.yudream.plugin.voxelithmap.application.cmd;

import online.yudream.plugin.voxelithmap.domain.value.ModJarRef;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;

import java.util.List;

/**
 * 更新地图项目的入参；字段为 {@code null} 表示不改动。
 *
 * <p>{@code modJars} 例外：{@code null} = 不改动，<b>非 null（含空表）= 整体替换</b>
 * ——空表是合法的「清空全部模组」。</p>
 *
 * @param name            新展示名
 * @param worldFileId     新存档（换存档需要重新框选与渲染）
 * @param worldName       新存档名
 * @param clientJarFileId 新 client jar
 * @param clientJarName   新 client jar 的原始文件名（与 fileId 一起更新）
 * @param modsPackFileId  新模组包 zip 的平台文件 id（null = 不改动；空串 = 清除）
 * @param modsPackName    模组包原始文件名（展示用）
 * @param modJars         新模组 jar 列表（整体替换；null = 不改动）
 * @param settings        新渲染参数
 * @param regionMinX      框选范围（四个都给了才会应用）
 * @param regionMinZ      同上
 * @param regionMaxX      同上
 * @param regionMaxZ      同上
 * @param clearRegion     置 true 表示取消框选（渲染全图）
 */
public record UpdateMapCmd(
        String name,
        String worldFileId,
        String worldName,
        String clientJarFileId,
        String clientJarName,
        String modsPackFileId,
        String modsPackName,
        List<ModJarRef> modJars,
        RenderSettings settings,
        Integer regionMinX,
        Integer regionMinZ,
        Integer regionMaxX,
        Integer regionMaxZ,
        boolean clearRegion) {

    /** 是否提供了完整的框选范围。 */
    public boolean hasRegion() {
        return regionMinX != null && regionMinZ != null && regionMaxX != null && regionMaxZ != null;
    }
}
