package online.yudream.plugin.voxelithmap.domain.aggregate;

/** 一次发布代。不可变；用代 id 隔离产物目录，失败/取消不会污染可见代。 */
public record MapGeneration(String mapId, String id) {

    public static final int MAX_PUBLISHED_GENERATIONS = 3;

    public MapGeneration {
        if (mapId == null || mapId.isBlank()) {
            throw new IllegalArgumentException("mapId 不能为空");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("generationId 不能为空");
        }
    }

    /** 代产物的对象存储 key 前缀（不含插件命名空间，由平台补）。 */
    public String prefix() {
        return "maps/" + mapId + "/generations/" + id;
    }
}
