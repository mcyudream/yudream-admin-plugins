package online.yudream.plugin.voxelithmap.infrastructure.storage;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Voxelith 产物的对象存储布局。
 *
 * <p>代隔离是核心：所有渲染产物都写在 {@code maps/{mapId}/generations/{generationId}/}
 * 下，{@code manifest.json} 里的相对路径（{@code tiles/hires/0/0.glb}、{@code atlas.png}、
 * LOD 图集页）原样成为对象 key 的后半段。于是「导入」不需要理解瓦片金字塔结构——
 * 清单说什么就存什么，「删除」也只需按清单删除，不会误删别的代。</p>
 *
 * <pre>
 * maps/{mapId}/world.zip                                    上传的存档（渲染输入）
 * maps/{mapId}/client.jar                                   渲染用的原版 client jar
 * maps/{mapId}/markers.json                                 标注（与代无关，可随时编辑）
 * maps/{mapId}/generations/{gid}/manifest.json              清单本体
 * maps/{mapId}/generations/{gid}/atlas.png                  贴图集
 * maps/{mapId}/generations/{gid}/tiles/hires/{x}/{z}.glb     hires 瓦片
 * maps/{mapId}/generations/{gid}/tiles/lod/{lvl}/{x}/{z}.glb LOD 瓦片
 * maps/{mapId}/generations/{gid}/tiles/lod/{lvl}/lod-atlas.png  LOD 共享图集页（新引擎清单地址；
 *                                     旧引擎为 lod-atlas/{lvl}.png——一律以清单内地址为准）
 * maps/{mapId}/generations/{gid}/generation-manifest.txt    本代 key 清单（私有，不对外暴露）
 * </pre>
 *
 * <p>所有读取都经 {@link #find(String)}：宿主 {@code PluginFileStore.get} 在对象不存在时是
 * <b>抛异常</b>（S3 {@code NoSuchKey} → {@code BizException("文件不存在")}），而不是返回
 * {@code null}。直接透传会把「这张地图还没产物」放大成整个列表接口 400。</p>
 */
public final class MapArtifactStorage {

    public static final String MAPS_PREFIX = "maps/";

    /** 清单文件在代内的相对路径。 */
    public static final String MANIFEST_FILE = "manifest.json";
    /** 标注文件的相对路径（地图级，不在代内）。 */
    public static final String MARKERS_FILE = "markers.json";
    /** 代内私有 key 清单文件名。 */
    public static final String GENERATION_MANIFEST_FILE = "generation-manifest.txt";

    private final PluginFileStore store;

    public MapArtifactStorage(PluginFileStore store) {
        this.store = store;
    }

    // ---------- 单例文件 ----------

    public void saveWorldArchive(String mapId, byte[] zip) {
        putRaw(worldArchiveKey(mapId), zip, "application/zip");
    }

    public Optional<PluginStoredFile> worldArchive(String mapId) {
        return find(worldArchiveKey(mapId));
    }

    public void saveClientJar(String mapId, byte[] jar) {
        putRaw(clientJarKey(mapId), jar, "application/java-archive");
    }

    public Optional<PluginStoredFile> clientJar(String mapId) {
        return find(clientJarKey(mapId));
    }

    // ---------- 代产物 ----------

    /**
     * 按清单内的相对路径写入一份代产物。
     *
     * @param mapId       地图 id
     * @param generationId 发布代 id
     * @param relative    清单内的相对路径（{@code tiles/hires/0/0.glb} 等），必须是干净的相对路径
     * @param data        字节内容
     * @throws IllegalArgumentException 相对路径非法（绝对路径、含 {@code ..} 或反斜杠）
     */
    public void saveArtifact(String mapId, String generationId, String relative, byte[] data) {
        putRaw(artifactKey(mapId, generationId, relative), data, contentTypeOf(relative));
    }

    /** 按相对路径读取代产物。 */
    public Optional<PluginStoredFile> artifact(String mapId, String generationId, String relative) {
        return find(artifactKey(mapId, generationId, relative));
    }

    /** 写入清单本体。 */
    public void saveManifest(String mapId, String generationId, byte[] json) {
        saveArtifact(mapId, generationId, MANIFEST_FILE, json);
    }

    /** 读取清单本体。 */
    public Optional<PluginStoredFile> manifest(String mapId, String generationId) {
        return artifact(mapId, generationId, MANIFEST_FILE);
    }

    /** 标注始终在地图级（与代解耦：编辑标注不需要重渲染）。 */
    public void saveMarkers(String mapId, byte[] json) {
        putRaw(markersKey(mapId), json, "application/json");
    }

    public Optional<PluginStoredFile> markers(String mapId) {
        return find(markersKey(mapId));
    }

    /** 列表封面（管理员在查看页保存默认视角时的截图；地图级，与代解耦）。 */
    public void saveCover(String mapId, byte[] png) {
        putRaw(coverKey(mapId), png, "image/png");
    }

    public Optional<PluginStoredFile> cover(String mapId) {
        return find(coverKey(mapId));
    }

    /** 列表封面缩略图（保存封面时顺带生成；老封面由读取路径按需补生成）。 */
    public void saveCoverThumb(String mapId, byte[] jpeg) {
        putRaw(coverThumbKey(mapId), jpeg, "image/jpeg");
    }

    public Optional<PluginStoredFile> coverThumb(String mapId) {
        return find(coverThumbKey(mapId));
    }

    /**
     * 边车侧存档 id 的映射（引擎侧的元数据，不进领域聚合）。
     *
     * <p>边车自己生成存档 id（{@code slug-短哈希}），而它把解包后的世界放在自己的目录里，
     * 所以插件只需要记住「这张地图对应哪次上传」。存成地图级小文件而不是塞进项目文档，
     * 是为了让「边车数据被手工清空」这种情况可以自愈：文件没了就重新上传一次。</p>
     *
     * @param mapId    地图 id
     * @param sourceFileId 这次上传对应的平台文件 id（换存档后需重新上传）
     * @param uploadId 边车侧存档 id
     */
    public void saveSidecarLink(String mapId, String sourceFileId, String uploadId) {
        String json = "{\"sourceFileId\":" + quote(sourceFileId) + ",\"uploadId\":" + quote(uploadId) + "}";
        putRaw(sidecarLinkKey(mapId), json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /**
     * 读边车存档映射。
     *
     * @param mapId 地图 id
     * @return {@code [sourceFileId, uploadId]}；不存在或解析失败返回空
     */
    /** 实例导入的模组 jar（独立 key，创建地图后由删除流程按 key 清理）。 */
    public void saveImportedJar(String key, byte[] jar) {
        putRaw(key, jar, "application/java-archive");
    }

    /** 按 key 删除（imports 清理用）。 */
    public void deleteKey(String key) {
        store.delete(key);
    }

    /** 按原始 key 读取（实例同步的存档下载用）。 */
    public Optional<PluginStoredFile> fileByKey(String key) {
        return find(key);
    }

    /** 清掉边车映射（边车侧上传已失效时调用，下次预览会重新登记）。 */
    public void clearSidecarLink(String mapId) {
        store.delete(sidecarLinkKey(mapId));
    }

    public Optional<String[]> sidecarLink(String mapId) {
        Optional<PluginStoredFile> stored = find(sidecarLinkKey(mapId));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try (var input = stored.get().inputStream()) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String source = extractJsonString(json, "sourceFileId");
            String upload = extractJsonString(json, "uploadId");
            if (upload == null || upload.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new String[]{source == null ? "" : source, upload});
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 极简 JSON 字符串取值：只服务于上面这个两字段的小文件，不引解析器。 */
    private static String extractJsonString(String json, String field) {
        String needle = "\"" + field + "\"";
        int index = json.indexOf(needle);
        if (index < 0) {
            return null;
        }
        int colon = json.indexOf(':', index + needle.length());
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        int end = start < 0 ? -1 : json.indexOf('"', start + 1);
        return start < 0 || end < 0 ? null : json.substring(start + 1, end);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(character);
            }
        }
        return builder.append('"').toString();
    }

    public static String sidecarLinkKey(String mapId) {
        return MAPS_PREFIX + mapId + "/sidecar.json";
    }

    // ---------- 代的 key 清单（发布校验与回收的依据） ----------

    public void writeGenerationManifest(String mapId, String generationId, List<String> keys) {
        Set<String> unique = new LinkedHashSet<>(keys);
        String text = String.join("\n", unique);
        putRaw(generationManifestKey(mapId, generationId),
                text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    /** 读取代的 key 清单；不存在返回空列表。 */
    public List<String> readGenerationManifest(String mapId, String generationId) {
        Optional<PluginStoredFile> stored = find(generationManifestKey(mapId, generationId));
        if (stored.isEmpty()) {
            return List.of();
        }
        try (var input = stored.get().inputStream()) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            List<String> keys = new ArrayList<>();
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    keys.add(trimmed);
                }
            }
            return List.copyOf(keys);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 删除一代的全部产物。
     *
     * <p>只删清单里记过的 key：即使清单缺失（例如导入中途失败），也只会留下垃圾而不会
     * 误删其它代或其它地图的东西。单个 key 删除失败吞掉继续——目的是让地图记录能删干净。</p>
     */
    public void deleteGeneration(String mapId, String generationId) {
        for (String key : readGenerationManifest(mapId, generationId)) {
            if (key.startsWith(generationPrefix(mapId, generationId))) {
                try {
                    store.delete(key);
                } catch (RuntimeException ignored) {
                    // 部分失败不阻断整体回收
                }
            }
        }
        store.delete(generationManifestKey(mapId, generationId));
    }

    /** 删除地图的输入文件与标注。 */
    public void deleteMapInputs(String mapId) {
        store.delete(worldArchiveKey(mapId));
        store.delete(clientJarKey(mapId));
        store.delete(markersKey(mapId));
        store.delete(coverKey(mapId));
        store.delete(coverThumbKey(mapId));
    }

    // ---------- key 布局 ----------

    public static String artifactKey(String mapId, String generationId, String relative) {
        return generationPrefix(mapId, generationId) + "/" + requireRelative(relative);
    }

    public static String generationPrefix(String mapId, String generationId) {
        return MAPS_PREFIX + mapId + "/generations/" + generationId;
    }

    public static String markersKey(String mapId) {
        return MAPS_PREFIX + mapId + "/" + MARKERS_FILE;
    }

    public static String coverKey(String mapId) {
        return MAPS_PREFIX + mapId + "/cover.png";
    }

    public static String coverThumbKey(String mapId) {
        return MAPS_PREFIX + mapId + "/cover-thumb.jpg";
    }


    public static String worldArchiveKey(String mapId) {
        return MAPS_PREFIX + mapId + "/world.zip";
    }

    public static String clientJarKey(String mapId) {
        return MAPS_PREFIX + mapId + "/client.jar";
    }

    public static String generationManifestKey(String mapId, String generationId) {
        return generationPrefix(mapId, generationId) + "/" + GENERATION_MANIFEST_FILE;
    }

    /** 按扩展名推断 content type（瓦片是 glb，图集是 png，清单是 json）。 */
    public static String contentTypeOf(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".glb")) {
            return "model/gltf-binary";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".bin") || lower.endsWith(".gz")) {
            return "application/octet-stream";
        }
        return "application/octet-stream";
    }

    /**
     * 校验清单里的相对路径是「干净的相对路径」。
     *
     * <p>清单来自渲染子进程的产物，理论上可信；但发布前仍要挡住 {@code ../} 与绝对路径，
     * 否则一个被篡改的清单就能让导入把文件写到别人的代目录里（或读到不该读的对象）。</p>
     */
    public static String requireRelative(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("产物相对路径不能为空");
        }
        String value = relative.trim().replace('\\', '/');
        if (value.startsWith("/") || value.contains("..") || value.contains(":") || value.contains("//")) {
            throw new IllegalArgumentException("非法产物路径: " + relative);
        }
        return value;
    }

    /**
     * 读取对象；未命中时返回空。
     *
     * <p>{@link PluginFileStore#get(String)} 没有「未命中返回 null」的契约：宿主实现
     * （{@code ObjectStoragePluginFileStore} → S3）在对象不存在时抛
     * {@code BizException("文件不存在")}。存储层必须把「读不到」统一收敛成
     * {@code Optional.empty()}，这样「地图刚建好、还没渲染」与「对象真丢了」都表现为
     * 「还没有产物」，而不是让每个调用方各自去猜异常类型。</p>
     */
    private Optional<PluginStoredFile> find(String key) {
        try {
            return Optional.ofNullable(store.get(key));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void putRaw(String key, byte[] data, String contentType) {
        store.put(key, new ByteArrayInputStream(data), data.length, contentType);
    }
}
