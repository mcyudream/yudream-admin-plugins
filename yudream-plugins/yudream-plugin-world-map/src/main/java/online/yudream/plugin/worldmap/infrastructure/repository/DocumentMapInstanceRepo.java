package online.yudream.plugin.worldmap.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.worldmap.domain.aggregate.MapInstance;
import online.yudream.plugin.worldmap.domain.enumerate.MapState;
import online.yudream.plugin.worldmap.domain.repo.MapInstanceRepo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于插件文档存储的地图仓储实现。
 */
public class DocumentMapInstanceRepo implements MapInstanceRepo {

    private static final String COLLECTION = "map_instance";

    private final PluginDocumentStore store;

    public DocumentMapInstanceRepo(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public MapInstance save(MapInstance map) {
        store.save(COLLECTION, map.getId(), toDocument(map));
        return map;
    }

    @Override
    public Optional<MapInstance> findById(String id) {
        return store.findById(COLLECTION, id).map(this::toAggregate);
    }

    @Override
    public List<MapInstance> findAll() {
        long count = store.count(COLLECTION);
        return store.findAll(COLLECTION, 1, (int) Math.max(count, 1)).stream().map(this::toAggregate).toList();
    }

    @Override
    public void delete(String id) {
        store.delete(COLLECTION, id);
    }

    private Map<String, Object> toDocument(MapInstance map) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("name", map.getName());
        doc.put("dimension", map.getDimension());
        doc.put("state", map.getState().name());
        doc.put("spawnX", map.getSpawnX());
        doc.put("spawnY", map.getSpawnY());
        doc.put("spawnZ", map.getSpawnZ());
        doc.put("minY", map.getMinY());
        doc.put("maxY", map.getMaxY());
        doc.put("minTileX", map.getMinTileX());
        doc.put("minTileZ", map.getMinTileZ());
        doc.put("maxTileX", map.getMaxTileX());
        doc.put("maxTileZ", map.getMaxTileZ());
        doc.put("stripNetherCeiling", map.isStripNetherCeiling());
        doc.put("hiresTiles", map.getHiresTiles());
        doc.put("lowresTiles", map.getLowresTiles());
        doc.put("worldZipKey", map.getWorldZipKey());
        doc.put("clientJarKey", map.getClientJarKey());
        doc.put("activeGenerationId", map.getActiveGenerationId());
        doc.put("createdAt", map.getCreatedAt());
        doc.put("renderedAt", map.getRenderedAt());
        doc.put("message", map.getMessage());
        return doc;
    }

    @SuppressWarnings("unchecked")
    private MapInstance toAggregate(Map<String, Object> doc) {
        MapInstance map = new MapInstance(
                stringValue(doc.get("_id"), stringValue(doc.get("id"), "")),
                stringValue(doc.get("name"), "未命名地图"),
                stringValue(doc.get("dimension"), "overworld")
        );
        map.setState(MapState.valueOf(stringValue(doc.get("state"), "EMPTY")));
        map.setSpawnX(intValue(doc.get("spawnX")));
        map.setSpawnY(intValue(doc.get("spawnY"), 64));
        map.setSpawnZ(intValue(doc.get("spawnZ")));
        map.setMinY(intValue(doc.get("minY"), -64));
        map.setMaxY(intValue(doc.get("maxY"), 320));
        map.setMinTileX(intValue(doc.get("minTileX")));
        map.setMinTileZ(intValue(doc.get("minTileZ")));
        map.setMaxTileX(intValue(doc.get("maxTileX")));
        map.setMaxTileZ(intValue(doc.get("maxTileZ")));
        map.setStripNetherCeiling(Boolean.TRUE.equals(doc.get("stripNetherCeiling")));
        map.setHiresTiles(intValue(doc.get("hiresTiles")));
        map.setLowresTiles(intValue(doc.get("lowresTiles")));
        map.setWorldZipKey(stringValue(doc.get("worldZipKey"), null));
        map.setClientJarKey(stringValue(doc.get("clientJarKey"), null));
        map.setActiveGenerationId(stringValue(doc.get("activeGenerationId"), null));
        map.setCreatedAt(longValue(doc.get("createdAt")));
        map.setRenderedAt(longValue(doc.get("renderedAt")));
        map.setMessage(stringValue(doc.get("message"), null));
        return map;
    }

    static String stringValue(Object value, String fallback) {
        return value instanceof String text ? text : fallback;
    }

    static int intValue(Object value) {
        return intValue(value, 0);
    }

    static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
