package online.yudream.plugin.voxelithmap.domain.value;

import java.util.ArrayList;
import java.util.List;

/**
 * 已发布产物的清单摘要。
 *
 * <p>从 Voxelith 的 {@code manifest.json} 里抽取查看器与管理页真正需要的字段。
 * 清单本身（含逐瓦片 sha1）体积随地图大小线性增长，不适合每帧塞进文档库，
 * 因此只持久化摘要；逐瓦片明细始终以清单文件为准。</p>
 *
 * @param formatVersion  清单格式版本（1）
 * @param version        内容版本（全部瓦片 sha1 的聚合哈希），任一瓦片变化即变化
 * @param generatedAt    管线生成时间（ISO-8601）
 * @param tileCount      瓦片总数
 * @param hiresTileCount level 0 瓦片数
 * @param lodLevels      LOD 层数（{@code lodCount}）
 * @param hiresTileSize  hires 瓦片边长（方块）
 * @param boundsMin      包围盒最小角 [x,y,z]
 * @param boundsMax      包围盒最大角 [x,y,z]
 * @param atlasSize      贴图集宽（像素）
 * @param textureCount   入集贴图数
 * @param lodAtlasPages  LOD 共享图集页数量
 * @param bytes          全部瓦片字节合计
 */
public record MapManifestSummary(
        int formatVersion,
        String version,
        String generatedAt,
        int tileCount,
        int hiresTileCount,
        int lodLevels,
        int hiresTileSize,
        List<Double> boundsMin,
        List<Double> boundsMax,
        int atlasSize,
        int textureCount,
        int lodAtlasPages,
        long bytes) {

    public MapManifestSummary {
        boundsMin = boundsMin == null ? List.of() : List.copyOf(boundsMin);
        boundsMax = boundsMax == null ? List.of() : List.copyOf(boundsMax);
    }

    /** 尚未渲染过时的占位摘要。 */
    public static MapManifestSummary empty() {
        return new MapManifestSummary(1, "", "", 0, 0, 1, 32,
                List.of(0d, 0d, 0d), List.of(0d, 0d, 0d), 0, 0, 0, 0L);
    }

    /** 供管理页展示的一句话规模描述。 */
    public String describeScale() {
        if (tileCount <= 0) {
            return "尚无产物";
        }
        return "%d 个瓦片（hires %d，LOD %d 层），%.1f MB"
                .formatted(tileCount, hiresTileCount, lodLevels, bytes / 1024.0 / 1024.0);
    }

    /** 包围盒的水平范围，用于前端初始相机与「回填 minY」提示。 */
    public List<Double> horizontalBounds() {
        List<Double> result = new ArrayList<>(4);
        if (boundsMin.size() >= 3 && boundsMax.size() >= 3) {
            result.add(boundsMin.get(0));
            result.add(boundsMin.get(2));
            result.add(boundsMax.get(0));
            result.add(boundsMax.get(2));
        }
        return result;
    }
}
