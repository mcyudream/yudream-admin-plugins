package online.yudream.plugin.voxelithmap.domain.repo;

import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;

import java.util.List;
import java.util.Optional;

/** 地图项目仓储端口。 */
public interface MapProjectRepo {

    MapProject save(MapProject project);

    Optional<MapProject> findById(String mapId);

    List<MapProject> findAll();

    void delete(String mapId);

    long count();
}
