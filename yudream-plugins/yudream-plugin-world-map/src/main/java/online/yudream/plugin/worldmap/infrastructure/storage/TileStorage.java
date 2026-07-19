package online.yudream.plugin.worldmap.infrastructure.storage;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.ByteArrayInputStream;
import java.util.Optional;

/**
 * world-map 瓦片/资产存储：按 CONTRACT §6 布局封装 {@link PluginFileStore}。
 *
 * <p>PluginFileStore 本身已按插件命名空间隔离（即 §6 的 {@code plugins/world-map/} 前缀
 * 由平台处理），因此这里的 key 一律以 {@code maps/{mapId}/} 开头：</p>
 * <pre>
 * maps/{mapId}/world.zip                  上传的存档 zip（渲染输入源）
 * maps/{mapId}/client.jar                 渲染用客户端 jar（应用层约定位置，较 §6 简化）
 * maps/{mapId}/textures/atlas.png         贴图集
 * maps/{mapId}/tiles/hires/{tx}/{tz}.json.gz
 * maps/{mapId}/tiles/lowres/{lod}/{tx}/{tz}.png
 * </pre>
 */
public final class TileStorage {

    /** 地图对象根前缀。 */
    public static final String MAPS_PREFIX = "maps/";

    private final PluginFileStore store;

    public TileStorage(PluginFileStore store) {
        this.store = store;
    }

    // ---------- 类型化写入 ----------

    public void saveHires(String mapId, int tx, int tz, byte[] gzipJson) {
        put(hiresKey(mapId, tx, tz), gzipJson, "application/json");
    }

    public void saveLowres(String mapId, int lod, int tx, int tz, byte[] png) {
        put(lowresKey(mapId, lod, tx, tz), png, "image/png");
    }

    public void saveAtlas(String mapId, byte[] png) {
        put(atlasKey(mapId), png, "image/png");
    }

    public void saveWorldZip(String mapId, byte[] zip) {
        put(worldZipKey(mapId), zip, "application/zip");
    }

    public void saveClientJar(String mapId, byte[] jar) {
        put(clientJarKey(mapId), jar, "application/java-archive");
    }

    // ---------- 类型化读取 ----------

    public Optional<PluginStoredFile> hires(String mapId, int tx, int tz) {
        return Optional.ofNullable(store.get(hiresKey(mapId, tx, tz)));
    }

    public Optional<PluginStoredFile> lowres(String mapId, int lod, int tx, int tz) {
        return Optional.ofNullable(store.get(lowresKey(mapId, lod, tx, tz)));
    }

    public Optional<PluginStoredFile> atlas(String mapId) {
        return Optional.ofNullable(store.get(atlasKey(mapId)));
    }

    public Optional<PluginStoredFile> worldZip(String mapId) {
        return Optional.ofNullable(store.get(worldZipKey(mapId)));
    }

    public Optional<PluginStoredFile> clientJar(String mapId) {
        return Optional.ofNullable(store.get(clientJarKey(mapId)));
    }

    public Optional<PluginStoredFile> hires(String mapId, String generationId, int tx, int tz) {
        return Optional.ofNullable(store.get(hiresKey(mapId, generationId, tx, tz)));
    }

    public Optional<PluginStoredFile> lowres(String mapId, String generationId, int lod, int tx, int tz) {
        return Optional.ofNullable(store.get(lowresKey(mapId, generationId, lod, tx, tz)));
    }

    public Optional<PluginStoredFile> atlas(String mapId, String generationId) {
        return Optional.ofNullable(store.get(atlasKey(mapId, generationId)));
    }

    public Optional<PluginStoredFile> blueMapTextures(String mapId, String generationId) {
        return Optional.ofNullable(store.get(blueMapTexturesKey(mapId, generationId)));
    }

    public Optional<PluginStoredFile> blueMapSettings(String mapId, String generationId) {
        return Optional.ofNullable(store.get(blueMapSettingsKey(mapId, generationId)));
    }

    public Optional<PluginStoredFile> blueMapLowresIndex(String mapId, String generationId) {
        return Optional.ofNullable(store.get(blueMapLowresIndexKey(mapId, generationId)));
    }

    // ---------- 透传（供上层按自有 key 存取） ----------

    /** 按完整 key 写入。 */
    public void put(String key, byte[] data, String contentType) {
        store.put(key, new ByteArrayInputStream(data), data.length, contentType);
    }

    /** 按完整 key 读取；不存在返回 null。 */
    public PluginStoredFile get(String key) {
        return store.get(key);
    }

    /** 按完整 key 透传删除（供上层按 tile 清单逐个删除）。 */
    public void delete(String key) {
        store.delete(key);
    }

    /**
     * 删除地图的已知单例文件（存档/客户端 jar/贴图集）。
     * tile 数量不定，其 key 清单由上层（DocumentStore）维护，
     * 由上层逐个调用 {@link #delete(String)} 删除。
     */
    public void deleteMap(String mapId) {
        store.delete(worldZipKey(mapId));
        store.delete(clientJarKey(mapId));
        store.delete(atlasKey(mapId));
    }

    // ---------- key 布局（公开供上层拼装清单） ----------

    public static String hiresKey(String mapId, int tx, int tz) {
        return MAPS_PREFIX + mapId + "/tiles/hires/" + tx + "/" + tz + ".json.gz";
    }

    public static String hiresKey(String mapId, String generationId, int tx, int tz) {
        return generationPrefix(mapId, generationId) + "/tiles/hires/" + tx + "/" + tz + ".json.gz";
    }

    public static String blueMapHiresKey(String mapId, String generationId, int tx, int tz) {
        return generationPrefix(mapId, generationId) + "/tiles/hires/" + tx + "/" + tz + ".prbm";
    }

    public Optional<PluginStoredFile> blueMapHires(String mapId, String generationId, int tx, int tz) {
        return Optional.ofNullable(store.get(blueMapHiresKey(mapId, generationId, tx, tz)));
    }

    public static String lowresKey(String mapId, int lod, int tx, int tz) {
        return MAPS_PREFIX + mapId + "/tiles/lowres/" + lod + "/" + tx + "/" + tz + ".png";
    }

    public static String lowresKey(String mapId, String generationId, int lod, int tx, int tz) {
        return generationPrefix(mapId, generationId) + "/tiles/lowres/" + lod + "/" + tx + "/" + tz + ".png";
    }

    public static String atlasKey(String mapId) {
        return MAPS_PREFIX + mapId + "/textures/atlas.png";
    }

    public static String atlasKey(String mapId, String generationId) {
        return generationPrefix(mapId, generationId) + "/textures/atlas.png";
    }

    public static String blueMapTexturesKey(String mapId, String generationId) {
        return generationPrefix(mapId, generationId) + "/textures.json";
    }

    public static String blueMapSettingsKey(String mapId, String generationId) {
        return generationPrefix(mapId, generationId) + "/settings.json";
    }

    public static String blueMapLowresIndexKey(String mapId, String generationId) {
        return generationPrefix(mapId, generationId) + "/lowres-index.json";
    }

    private static String generationPrefix(String mapId, String generationId) {
        return MAPS_PREFIX + mapId + "/generations/" + generationId;
    }

    public static String worldZipKey(String mapId) {
        return MAPS_PREFIX + mapId + "/world.zip";
    }

    public static String clientJarKey(String mapId) {
        return MAPS_PREFIX + mapId + "/client.jar";
    }
}
