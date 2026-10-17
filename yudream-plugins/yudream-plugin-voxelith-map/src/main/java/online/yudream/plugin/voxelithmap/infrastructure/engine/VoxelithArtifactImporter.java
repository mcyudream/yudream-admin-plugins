package online.yudream.plugin.voxelithmap.infrastructure.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import online.yudream.plugin.voxelithmap.domain.value.MapManifestSummary;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 把 Voxelith 管线产物导入对象存储。
 *
 * <p>导入策略是「清单驱动」：只读 {@code manifest.json}，然后把它引用到的文件（贴图集、
 * 逐瓦片 glb、LOD 共享图集页）按**清单里的相对路径**原样搬进代目录。这样做的好处是</p>
 * <ul>
 *   <li>不需要理解瓦片金字塔、LOD 分层、增量图集扩行这些结构；</li>
 *   <li>引擎升级新增产物（例如将来多一种图集页）时导入侧无需改动；</li>
 *   <li>删除时按同一份 key 清单删，绝不会误删别的代。</li>
 * </ul>
 *
 * <p>刻意**不做**目录扫描：publish 目录里还有 {@code heightfield.bin}、{@code atlas-layout.json}
 * 等中间物，扫描会把它们一并搬进对象存储并对外暴露，白占空间。</p>
 */
public final class VoxelithArtifactImporter {

    private final MapArtifactStorage storage;
    private final ObjectMapper objectMapper;

    public VoxelithArtifactImporter(MapArtifactStorage storage, ObjectMapper objectMapper) {
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    /**
     * 导入 work 目录的地表栅格 {@code surface.png}（1px = 1 方块）到本代产物存储。
     *
     * <p>2D 地图用它做逐方块精度的平面图；随本代存储，代回收时一并清理。
     * work 目录没有该文件（旧渲染）返回 false，不影响发布。</p>
     */
    public boolean importSurface(Path workDir, String mapId, String generationId) throws IOException {
        Path surface = workDir.resolve("surface.png");
        if (!Files.isRegularFile(surface)) {
            return false;
        }
        storage.saveArtifact(mapId, generationId, "surface.png", Files.readAllBytes(surface));
        return true;
    }

    /**
     * 导入一代产物。
     *
     * @param mapId        地图 id
     * @param generationId 发布代 id
     * @param publishRoot  管线发布的单张地图目录（{@code <taskDir>/publish/<mapId>}）
     * @return 导入摘要
     * @throws IOException 清单缺失/不可解析
     */
    public ImportSummary importPublished(String mapId, String generationId, Path publishRoot) throws IOException {
        Path manifestFile = publishRoot.resolve(MapArtifactStorage.MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) {
            throw new IOException("渲染产物缺少清单: " + manifestFile);
        }
        JsonNode manifest = objectMapper.readTree(Files.readAllBytes(manifestFile));
        List<String> referenced = referencedFiles(manifest);
        if (referenced.size() <= 1) {
            throw new IOException("清单未引用任何瓦片，产物不完整");
        }

        Map<String, String> hashes = tileHashes(manifest);
        List<String> importedKeys = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        long importedBytes = 0L;

        for (String relative : referenced) {
            Path source = safeResolve(publishRoot, relative);
            if (!Files.isRegularFile(source)) {
                missing.add(relative);
                continue;
            }
            byte[] data = Files.readAllBytes(source);
            storage.saveArtifact(mapId, generationId, relative, data);
            importedKeys.add(MapArtifactStorage.artifactKey(mapId, generationId, relative));
            importedBytes += data.length;
        }

        // 标注单独存地图级：编辑标注不必重渲染，公开查看器也只需一个稳定地址
        Path markersFile = publishRoot.resolve(MapArtifactStorage.MARKERS_FILE);
        if (Files.isRegularFile(markersFile)) {
            storage.saveMarkers(mapId, Files.readAllBytes(markersFile));
        }

        storage.writeGenerationManifest(mapId, generationId, importedKeys);
        MapManifestSummary summary = summarize(manifest, hashes, importedBytes);
        return new ImportSummary(summary, List.copyOf(importedKeys), List.copyOf(missing));
    }

    /** 清单引用的全部文件（清单本体 + 贴图集 + LOD 图集页 + 全部瓦片）。 */
    static List<String> referencedFiles(JsonNode manifest) {
        Set<String> files = new LinkedHashSet<>();
        files.add(MapArtifactStorage.MANIFEST_FILE);
        text(manifest, "atlas", "url").ifPresent(url -> files.add(clean(url)));
        for (JsonNode page : manifest.path("lodAtlases")) {
            text(page, "url").ifPresent(url -> files.add(clean(url)));
        }
        for (JsonNode tile : manifest.path("tiles")) {
            text(tile, "url").ifPresent(url -> files.add(clean(url)));
        }
        return List.copyOf(files);
    }

    /** 逐瓦片 sha1（用于校验导入完整性）。 */
    private static Map<String, String> tileHashes(JsonNode manifest) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode tile : manifest.path("tiles")) {
            String url = text(tile, "url").orElse(null);
            String sha1 = text(tile, "sha1").orElse(null);
            if (url != null && sha1 != null) {
                result.put(clean(url), sha1);
            }
        }
        return result;
    }

    private static MapManifestSummary summarize(JsonNode manifest, Map<String, String> hashes, long bytes) {
        JsonNode tiles = manifest.path("tiles");
        int hiresCount = 0;
        long declaredBytes = 0L;
        for (JsonNode tile : tiles) {
            if (tile.path("level").asInt(-1) == 0) {
                hiresCount++;
            }
            declaredBytes += tile.path("bytes").asLong(0L);
        }
        return new MapManifestSummary(
                manifest.path("formatVersion").asInt(1),
                manifest.path("version").asText(""),
                manifest.path("generatedAt").asText(""),
                tiles.size(),
                hiresCount,
                manifest.path("settings").path("lodCount").asInt(1),
                manifest.path("settings").path("hiresTileSize").asInt(32),
                vec3(manifest.path("boundsMin")),
                vec3(manifest.path("boundsMax")),
                manifest.path("atlas").path("size").asInt(0),
                manifest.path("atlas").path("textureCount").asInt(0),
                manifest.path("lodAtlases").size(),
                declaredBytes > 0 ? declaredBytes : bytes);
    }

    private static List<Double> vec3(JsonNode node) {
        List<Double> values = new ArrayList<>(3);
        if (node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.asDouble(0d));
            }
        }
        return values;
    }

    private static Optional<String> text(JsonNode node, String field) {
        return text(node.path(field));
    }

    private static Optional<String> text(JsonNode node, String parent, String field) {
        return text(node.path(parent).path(field));
    }

    private static Optional<String> text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        String value = node.asText("");
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String clean(String relative) {
        return relative.replace('\\', '/');
    }

    /**
     * 把清单里的相对路径安全地落到 publish 目录内。
     *
     * @throws IOException 路径逃出 publish 目录
     */
    static Path safeResolve(Path publishRoot, String relative) throws IOException {
        MapArtifactStorage.requireRelative(relative);
        Path resolved = publishRoot.resolve(relative).normalize();
        if (!resolved.startsWith(publishRoot.normalize())) {
            throw new IOException("清单引用了 publish 目录之外的路径: " + relative);
        }
        return resolved;
    }

    /**
     * 导入结果。
     *
     * @param summary      清单摘要
     * @param importedKeys 写入对象存储的完整 key 列表（已登记为代清单）
     * @param missing      清单引用但盘上缺失的文件（非空即视为产物不完整）
     */
    public record ImportSummary(MapManifestSummary summary, List<String> importedKeys, List<String> missing) {

        /** 产物是否完整。 */
        public boolean complete() {
            return missing.isEmpty();
        }

        /** 供失败原因展示：最多列 5 个缺失文件。 */
        public String missingDescription() {
            if (missing.isEmpty()) {
                return "";
            }
            List<String> head = missing.size() > 5 ? missing.subList(0, 5) : missing;
            String suffix = missing.size() > 5 ? " 等 " + missing.size() + " 个" : "";
            return "清单引用的产物缺失: " + String.join(", ", head) + suffix;
        }
    }
}
