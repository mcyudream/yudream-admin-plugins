package online.yudream.plugin.voxelithmap.infrastructure.repository;

import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.value.DefaultView;
import online.yudream.plugin.voxelithmap.domain.enumerate.ProjectState;
import online.yudream.plugin.voxelithmap.domain.repo.MapProjectRepo;
import online.yudream.plugin.voxelithmap.domain.value.MapManifestSummary;
import online.yudream.plugin.voxelithmap.domain.value.ModJarRef;
import online.yudream.plugin.voxelithmap.domain.value.RegionWindow;
import online.yudream.plugin.voxelithmap.domain.value.RenderSettings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 地图项目仓储（{@code map_project} 集合）。
 *
 * <p>文档映射手工逐字段处理，不引第三方映射框架：字段少、显式写反而更耐升级，
 * 而且读的时候必须对每个字段兜底——老版本记录里缺字段是常态，不该因此抛异常
 * 让整个地图列表打不开。</p>
 */
public final class DocumentMapProjectRepo implements MapProjectRepo {

    public static final String COLLECTION = "map_project";

    /** 宿主文档库单页上限（超过要翻页）。 */
    private static final int PAGE_SIZE = 200;

    private final PluginDocumentStore store;

    public DocumentMapProjectRepo(PluginDocumentStore store) {
        this.store = store;
    }

    @Override
    public MapProject save(MapProject project) {
        store.save(COLLECTION, project.id(), toDocument(project));
        return project;
    }

    @Override
    public Optional<MapProject> findById(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return Optional.empty();
        }
        return store.findById(COLLECTION, mapId).map(this::toAggregate);
    }

    @Override
    public List<MapProject> findAll() {
        List<MapProject> projects = new ArrayList<>();
        long count = store.count(COLLECTION);
        int pages = (int) Math.max(1, Math.ceil(count / (double) PAGE_SIZE));
        for (int page = 1; page <= pages; page++) {
            for (Map<String, Object> document : store.findAll(COLLECTION, page, PAGE_SIZE)) {
                projects.add(toAggregate(document));
            }
        }
        return List.copyOf(projects);
    }

    @Override
    public void delete(String mapId) {
        store.delete(COLLECTION, mapId);
    }

    @Override
    public long count() {
        return store.count(COLLECTION);
    }

    // ---------- 映射 ----------

    Map<String, Object> toDocument(MapProject project) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", project.name());
        document.put("worldName", project.worldName());
        document.put("worldFileId", project.worldFileId());
        document.put("clientJarFileId", project.clientJarFileId());
        document.put("clientJarName", project.clientJarName());
        // mod jar 记成 [{fileId, name}]：宿主对象存储不保留原名，展示与日志都要靠插件自己记
        document.put("modJars", project.modJars().stream()
                .map(modJar -> {
                    Map<String, Object> modDoc = new LinkedHashMap<>();
                    modDoc.put("fileId", modJar.fileId());
                    modDoc.put("name", modJar.name());
                    return modDoc;
                })
                .toList());
        document.put("dimension", project.dimension());
        document.put("spawnX", project.spawnX());
        document.put("spawnY", project.spawnY());
        document.put("spawnZ", project.spawnZ());
        document.put("state", project.state().name());
        document.put("activeGenerationId", project.activeGenerationId());
        document.put("publishedGenerationIds", List.copyOf(project.publishedGenerationIds()));
        document.put("lastError", project.lastError());
        document.put("archived", project.archived());
        document.put("sourceInstanceId", project.sourceInstanceId().orElse(null));
        document.put("worldGroupId", project.worldGroup());
        document.put("coverRevision", project.coverRevision());
        document.put("modsPackFileId", project.modsPackFileId().orElse(null));
        document.put("modsPackName", project.modsPackName());
        document.put("createdAt", project.createdAt().toString());
        document.put("updatedAt", project.updatedAt().toString());

        RenderSettings settings = project.settings();
        Map<String, Object> settingsDoc = new LinkedHashMap<>();
        settingsDoc.put("dimension", settings.dimension());
        settingsDoc.put("minY", settings.minY());
        settingsDoc.put("maxLevel", settings.maxLevel());
        settingsDoc.put("sampleChunks", settings.sampleChunks());
        settingsDoc.put("lodAtlas", settings.lodAtlas());
        settingsDoc.put("meshopt", settings.meshopt());
        settingsDoc.put("maxChunks", settings.maxChunks());
        settingsDoc.put("batchChunks", settings.batchChunks());
        settingsDoc.put("skipHarvest", settings.skipHarvest());
        settingsDoc.put("mcVersion", settings.mcVersion());
        settingsDoc.put("loaderVersion", settings.loaderVersion());
        document.put("settings", settingsDoc);

        project.region().ifPresent(region -> {
            Map<String, Object> regionDoc = new LinkedHashMap<>();
            regionDoc.put("minX", region.minX());
            regionDoc.put("minZ", region.minZ());
            regionDoc.put("maxX", region.maxX());
            regionDoc.put("maxZ", region.maxZ());
            document.put("region", regionDoc);
        });

        project.defaultView().ifPresent(view -> {
            Map<String, Object> viewDoc = new LinkedHashMap<>();
            viewDoc.put("x", view.x());
            viewDoc.put("y", view.y());
            viewDoc.put("z", view.z());
            viewDoc.put("tx", view.tx());
            viewDoc.put("ty", view.ty());
            viewDoc.put("tz", view.tz());
            document.put("defaultView", viewDoc);
        });

        MapManifestSummary summary = project.summary();
        Map<String, Object> summaryDoc = new LinkedHashMap<>();
        summaryDoc.put("formatVersion", summary.formatVersion());
        summaryDoc.put("version", summary.version());
        summaryDoc.put("generatedAt", summary.generatedAt());
        summaryDoc.put("tileCount", summary.tileCount());
        summaryDoc.put("hiresTileCount", summary.hiresTileCount());
        summaryDoc.put("lodLevels", summary.lodLevels());
        summaryDoc.put("hiresTileSize", summary.hiresTileSize());
        summaryDoc.put("boundsMin", summary.boundsMin());
        summaryDoc.put("boundsMax", summary.boundsMax());
        summaryDoc.put("atlasSize", summary.atlasSize());
        summaryDoc.put("textureCount", summary.textureCount());
        summaryDoc.put("lodAtlasPages", summary.lodAtlasPages());
        summaryDoc.put("bytes", summary.bytes());
        document.put("summary", summaryDoc);
        return document;
    }

    MapProject toAggregate(Map<String, Object> document) {
        RenderSettings settings = settingsOf(mapValue(document.get("settings")));
        MapManifestSummary summary = summaryOf(mapValue(document.get("summary")));
        return MapProject.restore(
                stringValue(document.get("_id"), stringValue(document.get("id"), "")),
                stringValue(document.get("name"), "未命名地图"),
                stringValue(document.get("worldName"), ""),
                stringValue(document.get("worldFileId"), ""),
                stringValue(document.get("clientJarFileId"), ""),
                stringValue(document.get("clientJarName"), ""),
                nullableString(document.get("modsPackFileId")),
                stringValue(document.get("modsPackName"), ""),
                modJarsOf(document.get("modJars")),
                stringValue(document.get("dimension"), settings.dimension()),
                doubleValue(document.get("spawnX"), 0),
                doubleValue(document.get("spawnY"), 64),
                doubleValue(document.get("spawnZ"), 0),
                settings,
                regionOf(document.get("region")),
                defaultViewOf(document.get("defaultView")),
                stateOf(document.get("state")),
                nullableString(document.get("activeGenerationId")),
                stringList(document.get("publishedGenerationIds")),
                summary,
                nullableString(document.get("lastError")),
                booleanValue(document.get("archived"), false),
                nullableString(document.get("sourceInstanceId")),
                longValue(document.get("coverRevision"), MapProject.UNRESOLVED_COVER_REVISION),
                // 老文档没有分组字段：交给聚合按存档派生（与新建地图同一规则），
                // 于是 2.1.21 一次导入建出的多张维度地图升级后照样能互相切换
                nullableString(document.get("worldGroupId")),
                instantOf(document.get("createdAt")),
                instantOf(document.get("updatedAt")));
    }

    private static RenderSettings settingsOf(Map<String, Object> doc) {
        RenderSettings defaults = RenderSettings.defaults();
        if (doc.isEmpty()) {
            return defaults;
        }
        return new RenderSettings(
                stringValue(doc.get("dimension"), defaults.dimension()),
                intValue(doc.get("minY"), defaults.minY()),
                intValue(doc.get("maxLevel"), defaults.maxLevel()),
                intValue(doc.get("sampleChunks"), defaults.sampleChunks()),
                booleanValue(doc.get("lodAtlas"), defaults.lodAtlas()),
                booleanValue(doc.get("meshopt"), defaults.meshopt()),
                intValue(doc.get("maxChunks"), defaults.maxChunks()),
                intValue(doc.get("batchChunks"), defaults.batchChunks()),
                booleanValue(doc.get("skipHarvest"), defaults.skipHarvest()),
                stringValue(doc.get("mcVersion"), defaults.mcVersion()),
                stringValue(doc.get("loaderVersion"), defaults.loaderVersion()));
    }

    private static MapManifestSummary summaryOf(Map<String, Object> doc) {
        if (doc.isEmpty()) {
            return MapManifestSummary.empty();
        }
        MapManifestSummary empty = MapManifestSummary.empty();
        return new MapManifestSummary(
                intValue(doc.get("formatVersion"), empty.formatVersion()),
                stringValue(doc.get("version"), ""),
                stringValue(doc.get("generatedAt"), ""),
                intValue(doc.get("tileCount"), 0),
                intValue(doc.get("hiresTileCount"), 0),
                intValue(doc.get("lodLevels"), 1),
                intValue(doc.get("hiresTileSize"), 32),
                doubleList(doc.get("boundsMin")),
                doubleList(doc.get("boundsMax")),
                intValue(doc.get("atlasSize"), 0),
                intValue(doc.get("textureCount"), 0),
                intValue(doc.get("lodAtlasPages"), 0),
                longValue(doc.get("bytes"), 0L));
    }

    /** 读回模组列表：老记录没有这个字段返回空表；单个条目坏了跳过，不让整条记录读不出来。 */
    private static List<ModJarRef> modJarsOf(Object raw) {
        List<ModJarRef> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> doc = mapValue(item);
                String fileId = stringValue(doc.get("fileId"), "");
                if (!fileId.isBlank()) {
                    result.add(new ModJarRef(fileId, stringValue(doc.get("name"), "")));
                }
            }
        }
        return List.copyOf(result);
    }

    private static RegionWindow regionOf(Object raw) {        Map<String, Object> doc = mapValue(raw);
        if (doc.isEmpty()) {
            return null;
        }
        try {
            return new RegionWindow(
                    intValue(doc.get("minX"), 0),
                    intValue(doc.get("minZ"), 0),
                    intValue(doc.get("maxX"), 0),
                    intValue(doc.get("maxZ"), 0));
        } catch (IllegalArgumentException e) {
            // 老记录里的范围坏了：当作「未框选」而不是让整条记录读不出来
            return null;
        }
    }

    /** 读回默认视角：老记录没有这个字段返回空；数值坏了同样当「未设置」，不让整条记录读不出来。 */
    private static DefaultView defaultViewOf(Object raw) {
        Map<String, Object> doc = mapValue(raw);
        if (doc.isEmpty()) {
            return null;
        }
        try {
            return new DefaultView(
                    doubleValue(doc.get("x"), 0),
                    doubleValue(doc.get("y"), 0),
                    doubleValue(doc.get("z"), 0),
                    doubleValue(doc.get("tx"), 0),
                    doubleValue(doc.get("ty"), 0),
                    doubleValue(doc.get("tz"), 0));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ProjectState stateOf(Object raw) {        String value = stringValue(raw, "");
        try {
            return value.isBlank() ? ProjectState.EMPTY : ProjectState.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ProjectState.EMPTY;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String stringValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw);
        return value.isBlank() ? fallback : value;
    }

    private static String nullableString(Object raw) {
        String value = stringValue(raw, "");
        return value.isBlank() ? null : value;
    }

    private static int intValue(Object raw, int fallback) {
        return raw instanceof Number number ? number.intValue() : fallback;
    }

    private static long longValue(Object raw, long fallback) {
        return raw instanceof Number number ? number.longValue() : fallback;
    }

    private static double doubleValue(Object raw, double fallback) {
        return raw instanceof Number number ? number.doubleValue() : fallback;
    }

    private static boolean booleanValue(Object raw, boolean fallback) {
        return raw instanceof Boolean value ? value : fallback;
    }

    private static List<String> stringList(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Double> doubleList(Object raw) {
        List<Double> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number number) {
                    result.add(number.doubleValue());
                }
            }
        }
        return List.copyOf(result);
    }

    private static Instant instantOf(Object raw) {
        String value = stringValue(raw, "");
        if (value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }
}
