package online.yudream.plugin.worldmap.domain.repo;

import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;

import java.util.List;
import java.util.Optional;

public interface MapInstanceRepo {

    MapInstance save(MapInstance map);

    Optional<MapInstance> findById(String id);

    List<MapInstance> findAll();

    void delete(String id);
}
