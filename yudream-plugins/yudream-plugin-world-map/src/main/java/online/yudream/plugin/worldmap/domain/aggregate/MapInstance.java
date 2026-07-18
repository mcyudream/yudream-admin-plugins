package online.yudream.plugin.worldmap.domain.aggregate;

import online.yudream.plugin.worldmap.domain.enumerate.MapState;

/**
 * 地图实例聚合根：一个维度的存档渲染产物。
 */
public class MapInstance {

    private final String id;
    private String name;
    private String dimension;
    private MapState state;
    private int spawnX;
    private int spawnY;
    private int spawnZ;
    private int minY;
    private int maxY;
    private int minTileX;
    private int minTileZ;
    private int maxTileX;
    private int maxTileZ;
    private boolean stripNetherCeiling;
    private int hiresTiles;
    private int lowresTiles;
    private String worldZipKey;
    private String clientJarKey;
    private String activeGenerationId;
    private long createdAt;
    private long renderedAt;
    private String message;

    public MapInstance(String id, String name, String dimension) {
        this.id = id;
        this.name = name;
        this.dimension = dimension;
        this.state = MapState.EMPTY;
        this.spawnY = 64;
        this.minY = -64;
        this.maxY = 320;
        this.createdAt = System.currentTimeMillis();
    }

    public void markRendering() {
        this.state = MapState.RENDERING;
    }

    public void markReady(int hiresTiles, int lowresTiles) {
        if (state == MapState.CANCELLED) {
            return;
        }
        this.state = MapState.READY;
        this.hiresTiles = hiresTiles;
        this.lowresTiles = lowresTiles;
        this.renderedAt = System.currentTimeMillis();
    }

    public void markFailed(String message) {
        this.state = MapState.FAILED;
        this.message = message;
    }

    public void markCancelled(String message) {
        this.state = MapState.CANCELLED;
        this.message = message;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }
    public MapState getState() { return state; }
    public void setState(MapState state) { this.state = state; }
    public int getSpawnX() { return spawnX; }
    public void setSpawnX(int spawnX) { this.spawnX = spawnX; }
    public int getSpawnY() { return spawnY; }
    public void setSpawnY(int spawnY) { this.spawnY = spawnY; }
    public int getSpawnZ() { return spawnZ; }
    public void setSpawnZ(int spawnZ) { this.spawnZ = spawnZ; }
    public int getMinY() { return minY; }
    public void setMinY(int minY) { this.minY = minY; }
    public int getMaxY() { return maxY; }
    public void setMaxY(int maxY) { this.maxY = maxY; }
    public int getMinTileX() { return minTileX; }
    public void setMinTileX(int minTileX) { this.minTileX = minTileX; }
    public int getMinTileZ() { return minTileZ; }
    public void setMinTileZ(int minTileZ) { this.minTileZ = minTileZ; }
    public int getMaxTileX() { return maxTileX; }
    public void setMaxTileX(int maxTileX) { this.maxTileX = maxTileX; }
    public int getMaxTileZ() { return maxTileZ; }
    public void setMaxTileZ(int maxTileZ) { this.maxTileZ = maxTileZ; }
    public boolean isStripNetherCeiling() { return stripNetherCeiling; }
    public void setStripNetherCeiling(boolean stripNetherCeiling) { this.stripNetherCeiling = stripNetherCeiling; }
    public int getHiresTiles() { return hiresTiles; }
    public void setHiresTiles(int hiresTiles) { this.hiresTiles = hiresTiles; }
    public int getLowresTiles() { return lowresTiles; }
    public void setLowresTiles(int lowresTiles) { this.lowresTiles = lowresTiles; }
    public String getWorldZipKey() { return worldZipKey; }
    public void setWorldZipKey(String worldZipKey) { this.worldZipKey = worldZipKey; }
    public String getClientJarKey() { return clientJarKey; }
    public void setClientJarKey(String clientJarKey) { this.clientJarKey = clientJarKey; }
    public String getActiveGenerationId() { return activeGenerationId; }
    public void setActiveGenerationId(String activeGenerationId) { this.activeGenerationId = activeGenerationId; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getRenderedAt() { return renderedAt; }
    public void setRenderedAt(long renderedAt) { this.renderedAt = renderedAt; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
