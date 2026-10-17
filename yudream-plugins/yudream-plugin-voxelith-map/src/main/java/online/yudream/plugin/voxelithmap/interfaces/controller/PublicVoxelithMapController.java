package online.yudream.plugin.voxelithmap.interfaces.controller;

import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.plugin.voxelithmap.application.service.MapProjectAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import online.yudream.plugin.voxelithmap.domain.aggregate.MapProject;
import online.yudream.plugin.voxelithmap.domain.value.DefaultView;
import online.yudream.plugin.voxelithmap.infrastructure.storage.MapArtifactStorage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 公开地图接口（匿名可访问）：地图列表、清单、瓦片、图集、标注、全景。
 *
 * <p>路径刻意与 Voxelith 自己的静态服务布局逐字对齐（{@code /maps/{id}/manifest.json}、
 * {@code /maps/{id}/tiles/hires/{x}/{z}.glb}、{@code /maps/{id}/atlas.png} …）：前端渲染核心
 * 的 {@code loadManifest(base)} 与 {@code tileUrl(base, level, x, z)} 是从 URL 模板推导地址的，
 * 对齐之后 vendored 的渲染核心零改动就能读插件提供的瓦片。注意瓦片尾段自带 {@code .glb}
 * （新引擎模板），坐标统一经 {@link #glbName} 收敛，避免拼出双后缀。</p>
 *
 * <p>路径参数一律经 {@link RequestPaths#segments} / {@link RequestPaths#segment} 取：宿主给的
 * path 未做百分号解码，中文 id 直取会变成 {@code %E4%B8%87...}（详见该类注释）。</p>
 *
 * <p>端点是匿名开放的——三维地图本身就是要能对外分享的内容。数据范围不影响安全性：
 * 这里只暴露**已发布产物**，存档文件、平台文件 id、渲染参数都在管理端。</p>
 */
public class PublicVoxelithMapController {

    /** 代内产物不可变（URL 里带代 id），可以长缓存。 */
    private static final String IMMUTABLE_CACHE = "public, max-age=31536000, immutable";
    /** 兼容地址每次解析当前发布代，只能短缓存。 */
    private static final String ACTIVE_CACHE = "public, max-age=60, must-revalidate";
    /** 清单与标注变化频繁且体积小，直接禁用缓存。 */
    private static final String NO_CACHE = "no-cache";

    private final MapProjectAppService maps;
    private final MapArtifactStorage storage;

    public PublicVoxelithMapController(MapProjectAppService maps, MapArtifactStorage storage) {
        this.maps = maps;
        this.storage = storage;
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps")
    public PluginHttpResponse list(PluginHttpRequest request) {
        requireGet(request);
        return PluginHttpResponse.ok(Map.of("maps", maps.listPublic()));
    }

    /**
     * 同一世界的各维度地图（查看器的「切换维度」）。
     *
     * <p>暴露的信息与 {@link #list} 完全同源（都是已发布地图的公开摘要），因此不额外扩大公开面；
     * 未发布的维度不出现在结果里——切过去只会是空白。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/dimensions")
    public PluginHttpResponse dimensions(PluginHttpRequest request) {
        requireGet(request);
        return PluginHttpResponse.ok(Map.of("maps",
                maps.listPublicDimensions(RequestPaths.segment(request.path(), 1))));
    }

    /**
     * LOD 航拍图集页的**原始字节**直出（2D 地图的取图通道）。
     *
     * <p>为什么不用 {@code tiles/lod/{lvl}/lod-atlas.png}：部分网关对**图片扩展名路径**套静态文件
     * 规则（吞请求或挂起），而清单（.json）与瓦片（.glb）畅通——表现就是 2D 地图「一直加载中」。
     * 此端点路径不带图片扩展名、不带查询串，与清单同形；响应体就是 PNG 字节本身
     * （不再套 base64：大图集 base64 会 +1/3 体积，前端还得逐字符转换，白占主线程）。</p>
     */
    /**
     * 地表栅格（渲染时导入本代，1px = 1 方块）：2D 地图的首选底图。
     *
     * <p>逐方块精度，像素化渲染下缩放不糊；图像范围与清单包围盒一致，
     * 前端可做精确的世界坐标换算。本代缺失（旧渲染）返回 404，前端退用 LOD 图集页。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/surface.png")
    public PluginHttpResponse surface(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        byte[] png = readArtifact(project, "surface.png");
        if (png == null) {
            return atlasNotFound();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", IMMUTABLE_CACHE);
        return new PluginHttpResponse(200, headers, "image/png", png, false);
    }

    /**
     * 2D 地图的航拍图：当前渲染代**唯一 lod-atlas.png** 的降采样 JPEG。
     *
     * <p>按需从本代产物读取并降采样（长边 ≤4096），结果缓存——已渲染的地图无需重新渲染
     * 即可直接使用；体积从 PNG 的数 MB～数十 MB 降到 1～2MB，浏览器解码也快得多。
     * 降采样失败（极简 JRE 缺 ImageIO 等）时退回原图字节。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/lod-atlas.png")
    public PluginHttpResponse lodAtlasImage(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        byte[] png = atlasBytesOrNull(project);
        if (png == null) {
            return atlasNotFound();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", ACTIVE_CACHE);
        byte[] image = atlasImageCache.get(project.id() + ":" + project.activeGenerationId());
        if (image == null) {
            image = online.yudream.plugin.voxelithmap.infrastructure.image.AtlasImageImporter
                    .jpeg(png, online.yudream.plugin.voxelithmap.infrastructure.image.AtlasImageImporter.MAX_EDGE)
                    .orElse(png);
            atlasImageCache.clear();
            atlasImageCache.put(project.id() + ":" + project.activeGenerationId(), image);
        }
        return new PluginHttpResponse(200, headers, "image/jpeg", image, false);
    }

    /** 降采样航拍图缓存（与原图缓存同步单条覆盖）。 */
    private final Map<String, byte[]> atlasImageCache = new LinkedHashMap<>();

    /** 图集分片大小：与已验证畅通的瓦片/清单响应同一尺寸级别——
        个别网关对**单个大响应**会挂起（这正是数据端点「一直进行中」观察到的形态），分片绕开它。 */
    private static final int ATLAS_CHUNK_SIZE = 512 * 1024;

    /** 图集字节的单条缓存：分片取图会连续请求同一文件，避免每片都整读一遍
        （整读 × 片数是 O(n²) 磁盘量，大图集会把分片拖到分钟级）。单条覆盖即可。 */
    private final Map<String, byte[]> atlasCache = new LinkedHashMap<>();

    /** 只缓存 64MB 以内的图集；更大的图分片请求按无缓存处理（逐片整读，慢但可用）。 */
    private static final int MAX_ATLAS_CACHE_BYTES = 64 * 1024 * 1024;

    /**
     * 读出**当前渲染代的唯一航拍图** {@code tiles/lod/1/lod-atlas.png} 的原始字节；
     * 该路径缺失时（多层级渲染等少见布局）按清单挑 level 最小的一页兜底；都没有返回 null。
     *
     * <p>结果按「mapId:代id」缓存（单条覆盖）：分片取图一次会发几十上百个分片请求，
     * 全部指向同一个文件——不缓存的话每片都整读一遍，磁盘量是 O(片数 × 文件大小)。</p>
     */
    private byte[] atlasBytesOrNull(MapProject project) {
        String cacheKey = project.id() + ":" + project.activeGenerationId();
        byte[] cached = atlasCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        // 正常布局：当前渲染代的唯一 lod-atlas.png
        byte[] png = readArtifact(project, "tiles/lod/1/lod-atlas.png");
        if (png == null) {
            // 兜底：多层级渲染等少见布局——按清单挑 level 最小（分辨率最高）的一页
            Optional<AtlasPageRef> page = manifestJson(project)
                    .flatMap(PublicVoxelithMapController::preferredAtlasPage);
            if (page.isPresent()) {
                png = readArtifact(project, page.get().url());
            }
        }
        if (png != null && png.length <= MAX_ATLAS_CACHE_BYTES) {
            atlasCache.clear();
            atlasCache.put(cacheKey, png);
        }
        return png;
    }

    /** 读取本代产物的一个相对路径文件；缺失或路径非法返回 null。 */
    private byte[] readArtifact(MapProject project, String relative) {
        try {
            return storage.artifact(project.id(), project.activeGenerationId(),
                            MapArtifactStorage.requireRelative(relative))
                    .map(PublicVoxelithMapController::readAll)
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private PluginHttpResponse atlasNotFound() {
        return notFound();
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/lod-atlas-data")
    public PluginHttpResponse lodAtlasData(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        byte[] png = atlasBytesOrNull(project);
        if (png == null) {
            return atlasNotFound();
        }
        AtlasPageRef page = manifestJson(project)
                .flatMap(PublicVoxelithMapController::preferredAtlasPage).orElseThrow();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", ACTIVE_CACHE);
        headers.put("X-Atlas-Sha1", page.sha1());
        headers.put("X-Atlas-Level", String.valueOf(page.level()));
        return new PluginHttpResponse(200, headers, "image/png", png, false);
    }

    /**
     * 图集元信息（分片取图第一步）：当前渲染代唯一 lod-atlas.png 的字节数、分片大小、内容哈希。
     *
     * <p>响应体是手写的极小 JSON 且 {@code wrapped=false}：前端用原生 fetch 取，
     * 不经宿主的包装序列化，避免包装形态差异。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/lod-atlas-data/meta")
    public PluginHttpResponse lodAtlasDataMeta(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        byte[] png = atlasBytesOrNull(project);
        if (png == null) {
            return atlasNotFound();
        }
        String sha1 = manifestJson(project)
                .flatMap(PublicVoxelithMapController::preferredAtlasPage)
                .map(AtlasPageRef::sha1).orElse("");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", NO_CACHE);
        String json = "{\"size\":" + png.length + ",\"chunkSize\":" + ATLAS_CHUNK_SIZE
                + ",\"sha1\":\"" + sha1 + "\"}";
        return new PluginHttpResponse(200, headers, "application/json", json, false);
    }

    /**
     * 图集第 {@code index} 片（0 起，每片 {@link #ATLAS_CHUNK_SIZE} 字节，末片可短）。
     *
     * <p>为什么分片：前端逐片取、逐片拼装，单响应始终保持在瓦片级别——
     * 「整个数据端点一直进行中」的形态即单个大响应在网关层被挂起。</p>
     *
     * <p>路径刻意用独立的 {@code lod-atlas-chunks} 前缀而**不是**
     * {@code lod-atlas-data/{index}}：后者的 {@code {index}} 是通配段，会把
     * {@code /lod-atlas-data/meta} 也匹配进来（meta 被当 index 解析 → 404）。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/lod-atlas-chunks/{index}")
    public PluginHttpResponse lodAtlasDataChunk(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        byte[] png = atlasBytesOrNull(project);
        if (png == null) {
            return atlasNotFound();
        }
        int index;
        try {
            index = Integer.parseInt(RequestPaths.segment(request.path(), 2));
        } catch (NumberFormatException e) {
            return atlasNotFound();
        }
        int from = index * ATLAS_CHUNK_SIZE;
        if (from < 0 || from >= png.length) {
            return atlasNotFound();
        }
        int to = Math.min(png.length, from + ATLAS_CHUNK_SIZE);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cache-Control", ACTIVE_CACHE);
        return new PluginHttpResponse(200, headers, "application/octet-stream",
                java.util.Arrays.copyOfRange(png, from, to), false);
    }

    /** 清单里的一页 LOD 图集（挑页结果）；包内可见，供 URL 布局回归测试断言。 */
    record AtlasPageRef(String url, String sha1, int level) {
    }

    /** 读清单 JSON；读不到或解析失败返回空（上层按「没有图集」404）。 */
    private Optional<com.fasterxml.jackson.databind.JsonNode> manifestJson(MapProject project) {
        return storage.manifest(project.id(), project.activeGenerationId()).flatMap(file -> {
            try (var input = file.inputStream()) {
                return Optional.of(new ObjectMapper().readTree(input));
            } catch (Exception e) {
                return Optional.<com.fasterxml.jackson.databind.JsonNode>empty();
            }
        });
    }

    /**
     * 挑「最好」的一页图集：level 最小（分辨率最高）、url 非空；没有可用页返回空。
     *
     * <p>包内可见便于对挑页规则做回归测试：挑错页（高 level 低分辨率）只是模糊，
     * 挑到空 url 页则必然 404。</p>
     */
    static Optional<AtlasPageRef> preferredAtlasPage(com.fasterxml.jackson.databind.JsonNode manifest) {
        com.fasterxml.jackson.databind.JsonNode pages = manifest.path("lodAtlases");
        if (!pages.isArray() || pages.isEmpty()) {
            return Optional.empty();
        }
        com.fasterxml.jackson.databind.JsonNode best = null;
        for (com.fasterxml.jackson.databind.JsonNode page : pages) {
            if (page.path("url").asText("").isBlank()) {
                continue;
            }
            if (best == null
                    || page.path("level").asInt(Integer.MAX_VALUE) < best.path("level").asInt(Integer.MAX_VALUE)) {
                best = page;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(new AtlasPageRef(
                best.path("url").asText(),
                best.path("sha1").asText(),
                best.path("level").asInt()));
    }

    private static byte[] readAll(PluginStoredFile file) {
        try (var input = file.inputStream()) {
            return input.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("产物读取失败", e);
        }
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/manifest.json")
    public PluginHttpResponse manifest(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        return artifact(project, "manifest.json", "application/json", NO_CACHE);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/tiles/hires/{tx}/{tz}")
    public PluginHttpResponse hiresTile(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = RequestPaths.segments(request.path());
        MapProject project = maps.requirePublished(segments[2]);
        String relative = hiresRelative(segments);
        return artifact(project, relative, "model/gltf-binary", ACTIVE_CACHE);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/tiles/lod/{level}/{tx}/{tz}")
    public PluginHttpResponse lodTile(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = RequestPaths.segments(request.path());
        MapProject project = maps.requirePublished(segments[2]);
        String relative = lodRelative(segments);
        return artifact(project, relative, "model/gltf-binary", ACTIVE_CACHE);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/atlas.png")
    public PluginHttpResponse atlas(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        return artifact(project, "atlas.png", "image/png", ACTIVE_CACHE);
    }

    /**
     * LOD 分层图集页；文件名由清单里的相对地址决定，所以用通配段而不是写死层级。
     *
     * <p>旧引擎清单把页地址写作 {@code lod-atlas/{lvl}.png}；新引擎（4200402+）写作
     * {@code tiles/lod/{lvl}/lod-atlas.png}（清单驱动存储，页按清单地址原样落 key），
     * 两种地址都保留端点。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/lod-atlas/{name}")
    public PluginHttpResponse lodAtlasPage(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = RequestPaths.segments(request.path());
        MapProject project = maps.requirePublished(segments[2]);
        return artifact(project, "lod-atlas/" + segments[4], "image/png", ACTIVE_CACHE);
    }

    /** LOD 分层图集页（新引擎清单地址：{@code tiles/lod/{level}/{name}}）。 */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/tiles/lod/{level}/{name}")
    public PluginHttpResponse lodAtlasPageNewLayout(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = RequestPaths.segments(request.path());
        MapProject project = maps.requirePublished(segments[2]);
        String relative = lodAtlasNewLayoutRelative(segments);
        return artifact(project, relative,
                MapArtifactStorage.contentTypeOf(relative), ACTIVE_CACHE);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/markers.json")
    public PluginHttpResponse markers(PluginHttpRequest request) {
        requireGet(request);
        String mapId = RequestPaths.segment(request.path(), 1);
        maps.requirePublished(mapId);
        Optional<PluginStoredFile> file = storage.markers(mapId);
        return file.map(stored -> bytes(stored, "application/json", NO_CACHE))
                .orElseGet(() -> new PluginHttpResponse(200, Map.of("Cache-Control", NO_CACHE),
                        "application/json", "{\"sets\":[]}", false));
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/panorama.png")
    public PluginHttpResponse panorama(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        return artifact(project, "panorama.png", "image/png", ACTIVE_CACHE);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/panorama.json")
    public PluginHttpResponse panoramaMeta(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        return artifact(project, "panorama.json", "application/json", ACTIVE_CACHE);
    }

    /**
     * 默认初始视角（管理员在查看页设置）。响应 {@code {x,y,z,tx,ty,tz}}；
     * 未设置时返回空对象，查看器走内置机位。
     */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/view.json")
    public PluginHttpResponse defaultView(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        // 查看器用裸 fetch 读取（不走会自动拆 {code,data} 包装的 sdk.http），
        // 因此这里必须像 markers.json 一样回裸 JSON，包装层会把 x/tx 藏进 data 里
        Optional<DefaultView> view = project.defaultView();
        ObjectNode payload = new ObjectMapper().createObjectNode();
        if (view.isPresent()) {
            DefaultView v = view.get();
            payload.put("x", v.x());
            payload.put("y", v.y());
            payload.put("z", v.z());
            payload.put("tx", v.tx());
            payload.put("ty", v.ty());
            payload.put("tz", v.tz());
        }
        return new PluginHttpResponse(200, Map.of("Cache-Control", NO_CACHE),
                "application/json", payload.toString(), false);
    }

    /** 列表封面截图（管理员保存默认视角时生成；不存在返回 404）。 */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/cover.png")
    public PluginHttpResponse cover(PluginHttpRequest request) {
        requireGet(request);
        maps.require(RequestPaths.segment(request.path(), 1));
        Optional<PluginStoredFile> file = storage.cover(RequestPaths.segment(request.path(), 1));
        return file.map(stored -> bytes(stored, "image/png", ACTIVE_CACHE))
                .orElseGet(PublicVoxelithMapController::notFound);
    }

    /**
     * 列表封面缩略图（小尺寸 JPEG）。
     *
     * <p>画廊一张卡片只有几百像素宽，拉长边 1280 的 PNG 截屏纯属浪费：地图一多，全尺寸图
     * 会占满同源连接，把页面其它请求挤住（刷新时表现为无响应）。缩略图按封面版本号定位，
     * 内容随版本不可变，可以长缓存。老封面（缩略图机制之前保存的）由服务层按需补生成，
     * 生成不了时退回原封面，保证前端始终拿得到图。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/cover-thumb.jpg")
    public PluginHttpResponse coverThumb(PluginHttpRequest request) {
        requireGet(request);
        String mapId = RequestPaths.segment(request.path(), 1);
        maps.require(mapId);
        Optional<byte[]> thumb = maps.coverThumbnail(mapId);
        if (thumb.isPresent()) {
            // 原始字节 + 不包装：与 cover() 一样直接出图，前端 <img> 才拿得到
            return new PluginHttpResponse(200, Map.of("Cache-Control", IMMUTABLE_CACHE),
                    "image/jpeg", thumb.get(), false);
        }
        return storage.cover(mapId)
                .map(stored -> bytes(stored, "image/png", ACTIVE_CACHE))
                .orElseGet(PublicVoxelithMapController::notFound);
    }

    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/aerial.png")
    public PluginHttpResponse aerial(PluginHttpRequest request) {
        requireGet(request);
        MapProject project = maps.requirePublished(RequestPaths.segment(request.path(), 1));
        return artifact(project, "aerial.png", "image/png", ACTIVE_CACHE);
    }

    /**
     * 指定代内的任意产物（不可变地址，便于 CDN/浏览器长缓存与版本回放）。
     *
     * <p>相对路径由路径尾段拼出并逐段校验，{@code ..} 之类一律拒绝。</p>
     */
    @PluginHttpEndpoint(method = "GET", path = "/maps/{mapId}/generations/{generationId}/**")
    public PluginHttpResponse generationArtifact(PluginHttpRequest request) {
        requireGet(request);
        String[] segments = RequestPaths.segments(request.path());
        if (segments.length < 6) {
            return notFound();
        }
        String mapId = segments[2];
        String generationId = segments[4];
        // 相对路径由已解码的段拼出：不能用原始 path 做 substring，否则中文/转义段会带进 key
        String relative = RequestPaths.join(request.path(), 5);
        MapProject project = maps.requirePublished(mapId);
        if (!project.publishedGenerationIds().contains(generationId)) {
            return notFound();
        }
        try {
            String safe = MapArtifactStorage.requireRelative(relative);
            return storage.artifact(mapId, generationId, safe)
                    .map(file -> bytes(file, MapArtifactStorage.contentTypeOf(safe), IMMUTABLE_CACHE))
                    .orElseGet(PublicVoxelithMapController::notFound);
        } catch (IllegalArgumentException e) {
            return notFound();
        }
    }

    private PluginHttpResponse artifact(MapProject project, String relative, String contentType, String cache) {
        try {
            String safe = MapArtifactStorage.requireRelative(relative);
            return storage.artifact(project.id(), project.activeGenerationId(), safe)
                    .map(file -> bytes(file, contentType, cache))
                    .orElseGet(PublicVoxelithMapController::notFound);
        } catch (IllegalArgumentException e) {
            return notFound();
        }
    }

    private PluginHttpResponse bytes(PluginStoredFile file, String contentType, String cacheControl) {
        try (var input = file.inputStream()) {
            byte[] body = input.readAllBytes();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Cache-Control", cacheControl);
            String type = file.contentType() == null || file.contentType().isBlank()
                    ? contentType : file.contentType();
            return new PluginHttpResponse(200, headers, type, body, false);
        } catch (Exception e) {
            return new PluginHttpResponse(500, Map.of("Cache-Control", NO_CACHE),
                    "application/json", "{\"message\":\"产物读取失败\"}", false);
        }
    }

    private static PluginHttpResponse notFound() {
        return new PluginHttpResponse(404, Map.of("Cache-Control", NO_CACHE),
                "application/json", "{\"message\":\"产物不存在\"}", false);
    }

    private void requireGet(PluginHttpRequest request) {
        if (!"GET".equalsIgnoreCase(request.method())) {
            throw new IllegalArgumentException("仅支持 GET 请求");
        }
    }

    /**
     * 瓦片尾段收敛成 {@code {z}.glb} 形式。
     *
     * <p>新引擎（4200402+）的 {@code tileUrl} 模板自带 {@code .glb} 后缀
     * （{@code /tiles/hires/{x}/{z}.glb}），旧引擎不带；若直接再补一次后缀，
     * 存储里根本不存在 {@code {z}.glb.glb} 这种 key，所有瓦片 404。</p>
     */
    private static String glbName(String segment) {
        return segment.endsWith(".glb") ? segment : segment + ".glb";
    }

    /** 请求段 → hires 瓦片产物相对路径（包内可见，便于对 URL 布局做回归测试）。 */
    static String hiresRelative(String[] segments) {
        return "tiles/hires/" + segments[5] + "/" + glbName(segments[6]);
    }

    /** 请求段 → LOD 瓦片产物相对路径。 */
    static String lodRelative(String[] segments) {
        return "tiles/lod/" + segments[5] + "/" + segments[6] + "/" + glbName(segments[7]);
    }

    /** 请求段 → LOD 图集页产物相对路径（新引擎清单地址）。 */
    static String lodAtlasNewLayoutRelative(String[] segments) {
        return "tiles/lod/" + segments[5] + "/" + segments[6];
    }

}
