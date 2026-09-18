package online.yudream.base.plugin.ymcl.interfaces.controller;

import online.yudream.base.plugin.ymcl.interfaces.support.CasFilePreview;
import online.yudream.base.plugin.ymcl.interfaces.support.PathSegments;
import online.yudream.base.plugin.spi.annotation.PluginHttpEndpoint;
import online.yudream.base.plugin.spi.http.PluginHttpPart;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import online.yudream.base.plugin.ymcl.application.service.YmclEventBus;
import online.yudream.base.plugin.ymcl.bootstrap.YmclAdapterPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * YAP §7 MIP 分发面（写路径 + 读路径）。
 *
 * 上传通道（MIP WF-1 / 附录 B）：宿主 SPI 已支持 multipart——
 * - POST /mip/api/packs/{packId}/ingest        初始包：part "file" = 标准
 *   mrpack zip（modrinth.index.json + overrides + 可选 mip.json），服务端
 *   解包分流入库（CDN 引用只存 sources，overrides 入 CAS），mip.json 的
 *   features/policies 合并进 manifest（MIP §3.5）；
 * - POST /mip/api/packs/{packId}/ingest/delta  增量包：part "delta" = JSON
 *   索引 {baseVersion, version, channel, changed[], added[], deleted[],
 *   moved[]}，changed/added 条目的二进制按出现顺序放在 part
 *   "file0"、"file1"、…（避开用路径做 part name 的编码坑）；
 * - GET  /mip/api/packs/{packId}/versions      版本列表（MIP §4：releasedAt
 *   倒序，支持 ?channel= 过滤；yank 过的版本不出现）
 * - POST /mip/api/packs/{packId}/versions/{v}/yank  撤销发布（MIP §4：列表
 *   隐藏 + 版本标记 yanked，清单保留，已安装实例不受影响）
 * - GET  /mip/api/packs/{packId}/manifest/{v}  不可变 manifest（裸清单，
 *   CAS 源 URL 按请求来源重生成，不沿用发布时烘焙的 origin）
 * - GET  /mip/api/packs/{packId}/versions/{v}/mrpack?features=a,b
 *        首装 mrpack 下载（MIP WF-4：初次拿包走标准 mrpack 导入）；
 *        无 features 裁剪需求且有留存原件时直接回原件，否则服务端按
 *        manifest 合成（http 引用进 index，CAS 条目进 overrides，mip.json
 *        以精确路径再生成）
 * - GET  /mip/objects/{sha512}                 CAS 对象（公共分发）
 * - POST /mip/api/mirrors                      主动镜像登记（发布端）：把
 *   GitHub 托管的资源（如 Cleanroom loader 安装包/版本清单）推入 CAS 并按
 *   原始 URL 登记——玩家侧免 GitHub 即可完成安装
 * - GET  /mip/api/mirrors?url=                 镜像查询（公共面）：命中返回
 *   sha512 与对象下发地址，启动器下载 GitHub 资源前先查此处
 *
 * CAS（PluginFileStore，objectKey = "cas/{sha512}"）：sha512 即唯一信任锚。
 * 版本不可变：重复上传返回 409 version_exists（MIP WF-1）。镜像对象与 pack
 * 文件共用 CAS 去重；镜像登记按 url upsert（同 url 重推即刷新内容）。
 */
public class YmclMipPacksController {

    private static final String PACK_COLLECTION = "ymcl_packs";
    private static final String VERSION_COLLECTION = "ymcl_pack_versions";
    private static final String BINDING_COLLECTION = "ymcl_bindings";
    /** 主动镜像登记：doc id = 原始 URL（镜像键），值含 sha512/size/kind。 */
    private static final String MIRROR_COLLECTION = "ymcl_mirrors";
    private static final String CAS_PREFIX = "cas/";
    /** 首装留存的原始 mrpack：packs/{packId}@{version}.mrpack（同 versionKey 形态）。 */
    private static final String ARCHIVE_PREFIX = "packs/";
    private static final String ARCHIVE_SUFFIX = ".mrpack";
    /** modrinth.index.json dependencies 里识别的加载器键（优先级即列出顺序）。 */
    private static final List<String> LOADER_DEPENDENCY_KEYS =
            List.of("neoforge", "forge", "fabric-loader", "quilt-loader", "cleanroom");

    private final PluginFileStore files;
    private final PluginDocumentStore documents;
    private final YmclEventBus eventBus;

    public YmclMipPacksController(PluginFileStore files, PluginDocumentStore documents, YmclEventBus eventBus) {
        this.files = files;
        this.documents = documents;
        this.eventBus = eventBus;
    }

    /**
     * ObjectStoragePluginFileStore 对缺失键抛 BizException（"文件不存在"）
     * 而非返回空——此处按 null 语义兜底，匹配各调用方的 null 检查意图。
     */
    private PluginStoredFile getOrNull(String objectKey) {
        try {
            return files.get(objectKey);
        } catch (Exception miss) {
            return null;
        }
    }

    // ── Ingest：初始包（mrpack multipart，MIP WF-1） ─────────────────────

    @PluginHttpEndpoint(method = "POST", path = "/mip/api/packs/{packId}/ingest",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse ingest(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        String version = partText(request, "version");
        if (version == null || version.isBlank()) {
            return badRequest("invalid_body", "part 'version' is required");
        }
        PluginHttpPart archive = request.parts().get("file");
        if (archive == null || archive.data().length == 0) {
            return badRequest("invalid_body", "multipart part 'file' (mrpack zip) is required");
        }
        String versionKey = versionKey(packId, version);
        if (documents.findById(VERSION_COLLECTION, versionKey).isPresent()) {
            return badRequest(409, "version_exists", version + " already exists");
        }
        Map<String, Object> bind = bindPart(request);
        if (bind != null && bind.get("serverId") == null) {
            return badRequest("invalid_body", "bind.serverId is required when bind is present");
        }

        String channel = partText(request, "channel");
        String origin = YmclCapabilitiesController.resolveOrigin(request);
        List<Map<String, Object>> manifestFiles;
        List<Map<String, Object>> features;
        int externalRefs;
        String minecraftVersion;
        String loaderType;
        String loaderVersion;
        try {
            MrpackParsed parsed = parseMrpack(archive.data(), origin);
            applyMipJson(parsed);
            manifestFiles = parsed.entries;
            features = parsed.features;
            externalRefs = parsed.externalRefs;
            minecraftVersion = parsed.minecraftVersion;
            loaderType = parsed.loaderType;
            loaderVersion = parsed.loaderVersion;
        } catch (IllegalArgumentException error) {
            return badRequest("invalid_pack", String.valueOf(error.getMessage()));
        } catch (IOException error) {
            return badRequest("invalid_pack", "mrpack zip cannot be read: " + error.getMessage());
        }

        Map<String, Object> manifest = buildManifest(packId, version, channel, null, manifestFiles);
        if (features != null && !features.isEmpty()) {
            manifest.put("features", features);
        }
        Map<String, Object> game = gameSection(minecraftVersion, loaderType, loaderVersion);
        if (!game.isEmpty()) {
            manifest.put("game", game);
        }
        // 留存原始 mrpack：无 features 裁剪的首装下载直接回原件（MIP WF-4）。
        files.put(packArchiveKey(packId, version), new ByteArrayInputStream(archive.data()),
                (long) archive.data().length, "application/x-modrinth-modpack+zip");
        saveVersion(packId, version, channel, manifest);
        registerPackVersion(packId, version, channel);

        // 一键推送的绑定语义（YAP §7）：part "bind" {serverId, updatePolicy,
        // optional}，发布即投放，与 delta 保持对称；optional=true 合并写为
        // 原版增强包（玩家可选），缺省覆盖写为必装包。
        if (bind != null) {
            saveBinding(packId, version, channel, str(bind, "serverId"),
                    String.valueOf(bind.getOrDefault("updatePolicy", "prompt")),
                    Boolean.TRUE.equals(bind.get("optional")));
        }

        eventBus.publish(YmclEventBus.TYPE_PACK_PUBLISHED,
                Map.of("packId", packId, "version", version, "channel", channel == null ? "stable" : channel));
        Map<String, Object> report = ingestReport(manifest, 0);
        report.put("packId", packId);
        report.put("externalRefs", externalRefs);
        report.put("manifest", manifest);
        return PluginHttpResponse.rawJson(200, report);
    }

    // ── Ingest delta：增量包（multipart：JSON 索引 + file0..fileN） ───────

    @PluginHttpEndpoint(method = "POST", path = "/mip/api/packs/{packId}/ingest/delta",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse ingestDelta(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        PluginHttpPart deltaPart = request.parts().get("delta");
        if (deltaPart == null || deltaPart.data().length == 0) {
            return badRequest("invalid_body", "multipart part 'delta' is required");
        }
        Map<String, Object> body;
        try {
            body = json(deltaPart.text());
        } catch (Exception error) {
            return badRequest("invalid_body", "part 'delta' must be JSON");
        }
        String version = str(body, "version");
        String baseVersion = str(body, "baseVersion");
        if (version == null || version.isBlank() || baseVersion == null || baseVersion.isBlank()) {
            return badRequest("invalid_body", "version and baseVersion are required");
        }
        String versionKey = versionKey(packId, version);
        if (documents.findById(VERSION_COLLECTION, versionKey).isPresent()) {
            return badRequest(409, "version_exists", version + " already exists");
        }
        Optional<Map<String, Object>> baseDoc =
                documents.findById(VERSION_COLLECTION, versionKey(packId, baseVersion));
        if (baseDoc.isEmpty()) {
            return badRequest(404, "parent_not_found", "base version " + baseVersion + " not found");
        }
        Map<String, Object> baseManifest = castMap(baseDoc.get().get("manifest"));
        Map<String, Object> bind = castMap(body.get("bind"));
        if (bind != null && bind.get("serverId") == null) {
            return badRequest("invalid_body", "bind.serverId is required when bind is present");
        }

        String channel = str(body, "channel");
        String origin = YmclCapabilitiesController.resolveOrigin(request);
        Map<String, Object> manifest;
        try {
            manifest = composeDeltaManifest(baseManifest, body, request, origin);
        } catch (RuntimeException error) {
            return badRequest("invalid_file", String.valueOf(error.getMessage()));
        }
        manifest.put("pack_id", packId);
        manifest.put("version", version);
        manifest.put("parent", baseVersion);
        manifest.put("format_version", 1);
        manifest.put("channel", channel);
        // game：delta 显式携带（启动器自愈存量 manifest）优先，loader 缺省
        // 时从 base 继承；整体缺省则继承 base game。
        Map<String, Object> deltaGame = castMap(body.get("game"));
        Map<String, Object> baseGame = castMap(baseManifest.get("game"));
        if (deltaGame != null && str(deltaGame, "minecraft") != null) {
            Map<String, Object> game = new LinkedHashMap<>(deltaGame);
            if (game.get("loader") == null && baseGame != null && baseGame.get("loader") != null) {
                game.put("loader", baseGame.get("loader"));
            }
            manifest.put("game", game);
        } else if (baseGame != null && !baseGame.isEmpty()) {
            manifest.put("game", baseGame);
        }

        saveVersion(packId, version, channel, manifest);
        registerPackVersion(packId, version, channel);

        if (bind != null) {
            saveBinding(packId, version, channel, str(bind, "serverId"),
                    String.valueOf(bind.getOrDefault("updatePolicy", "prompt")),
                    Boolean.TRUE.equals(bind.get("optional")));
        }

        eventBus.publish(YmclEventBus.TYPE_PACK_PUBLISHED,
                Map.of("packId", packId, "version", version, "channel", channel == null ? "stable" : channel));
        Map<String, Object> report = ingestReport(manifest, body.size());
        report.put("packId", packId);
        report.put("manifest", manifest);
        return PluginHttpResponse.rawJson(200, report);
    }

    // ── Mirrors：GitHub 资源主动入 CAS（发布端推送，玩家侧公共查询） ─────

    /**
     * POST /mip/api/mirrors：发布端把 GitHub 托管的资源主动推入 CAS。
     * parts：url（原始地址，镜像键）、sha512（发布端计算的摘要，服务端
     * 复核）、kind（可选语义标识，如 loader-installer）、file（字节）。
     * 字节存 cas/{sha512}（与 pack 文件共享去重），登记按 url upsert——同
     * url 再推送即刷新镜像（版本清单等内容会演进的资源靠 upsert 换新）；
     * 摘要复核失败拒绝入库（不带病镜像）。
     */
    @PluginHttpEndpoint(method = "POST", path = "/mip/api/mirrors",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse putMirror(PluginHttpRequest request) {
        String url = partText(request, "url");
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return badRequest("invalid_body", "part 'url' must be an absolute http(s) URL");
        }
        String declaredSha = partText(request, "sha512");
        if (declaredSha == null) {
            return badRequest("invalid_body", "part 'sha512' is required");
        }
        PluginHttpPart file = request.parts().get("file");
        if (file == null || file.data().length == 0) {
            return badRequest("invalid_body", "multipart part 'file' (mirrored bytes) is required");
        }
        String sha512 = sha512(file.data());
        if (!sha512.equals(declaredSha.toLowerCase(Locale.ROOT))) {
            return badRequest("hash_mismatch", "declared sha512 does not match uploaded bytes");
        }
        // CAS 按 sha512 去重：put 对同键幂等（同摘要字节等值重写），不做
        // 先 get 探测——FileStore.get 对缺失对象是抛错而非返回 null。
        files.put(CAS_PREFIX + sha512, new ByteArrayInputStream(file.data()),
                (long) file.data().length, "application/octet-stream");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("url", url);
        doc.put("sha512", sha512);
        doc.put("size", file.data().length);
        doc.put("kind", partText(request, "kind"));
        doc.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(MIRROR_COLLECTION, url, doc);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", url);
        payload.put("sha512", sha512);
        payload.put("size", file.data().length);
        payload.put("objectUrl", YmclCapabilitiesController.resolveOrigin(request)
                + "/mip/objects/" + sha512);
        return PluginHttpResponse.rawJson(200, payload);
    }

    /**
     * GET /mip/api/mirrors?url=：镜像查询（匿名公共面，与 /objects 一致）。
     * 启动器下载 GitHub 资源前先查此处：命中即改走 CAS 对象（sha512 仍是
     * 唯一信任锚），未命中回退源站。
     */
    @PluginHttpEndpoint(method = "GET", path = "/mip/api/mirrors")
    public PluginHttpResponse getMirror(PluginHttpRequest request) {
        String url = query(request, "url");
        if (url == null) {
            return badRequest("invalid_body", "query 'url' is required");
        }
        Optional<Map<String, Object>> doc = documents.findById(MIRROR_COLLECTION, url);
        if (doc.isEmpty() || str(doc.get(), "sha512") == null) {
            return badRequest(404, "mirror_not_found", url);
        }
        String sha512 = str(doc.get(), "sha512");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", str(doc.get(), "url"));
        payload.put("sha512", sha512);
        payload.put("size", doc.get().get("size"));
        payload.put("kind", doc.get().get("kind"));
        payload.put("objectUrl", YmclCapabilitiesController.resolveOrigin(request)
                + "/mip/objects/" + sha512);
        return PluginHttpResponse.rawJson(200, payload);
    }

    // ── 管理读取（宿主管理页；协议面读路径见 /mip/api/packs/**） ──────────

    /** 管理端 pack 清单：全量翻页聚合（文档存储单页 200 上限）。 */
    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/packs",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse listPacks(PluginHttpRequest request) {
        List<Map<String, Object>> packs = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(PACK_COLLECTION, page, 200);
            if (batch.isEmpty()) {
                break;
            }
            packs.addAll(batch);
            page++;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("packs", packs);
        return PluginHttpResponse.json(200, payload);
    }

    /**
     * 编辑 pack 展示元数据（name/description/icon/defaultChannel）。
     * packId 本身不可改；版本清单仍由 ingest 写入，本端点只维护展示层。
     */
    @PluginHttpEndpoint(method = "PUT", path = "/v1/admin/packs/{packId}",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse updatePack(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        if (packId == null || packId.isBlank()) {
            return badRequest("invalid_body", "packId is required");
        }
        Map<String, Object> body;
        try {
            body = json(request.body());
        } catch (Exception error) {
            return badRequest("invalid_body", "Request body must be JSON");
        }
        Map<String, Object> pack = documents.findById(PACK_COLLECTION, packId).orElse(null);
        if (pack == null) {
            return badRequest(404, "pack_not_found", packId + " not found");
        }
        putOptionalTrimmed(pack, "name", body.get("name"));
        putOptionalTrimmed(pack, "description", body.get("description"));
        putOptionalTrimmed(pack, "icon", body.get("icon"));
        putOptionalTrimmed(pack, "defaultChannel", body.get("defaultChannel"));
        pack.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(PACK_COLLECTION, packId, pack);
        eventBus.publish(YmclEventBus.TYPE_PACK_UPDATED, Map.of("packId", packId));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pack", pack);
        return PluginHttpResponse.json(200, payload);
    }

    /**
     * 删除 pack 及其全部版本文档，并解除引用该 pack 的服务器绑定。
     * CAS 对象按 sha512 共享且可能被其它 pack 版本引用，删除后不回收。
     */
    @PluginHttpEndpoint(method = "DELETE", path = "/v1/admin/packs/{packId}",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse deletePack(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        if (packId == null || packId.isBlank()) {
            return badRequest("invalid_body", "packId is required");
        }
        Map<String, Object> pack = documents.findById(PACK_COLLECTION, packId).orElse(null);
        if (pack == null) {
            return badRequest(404, "pack_not_found", packId + " not found");
        }
        LinkedHashSet<String> versionKeys = new LinkedHashSet<>();
        for (Object item : castList(pack.get("versions"))) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String version = String.valueOf(entry.get("version"));
            if (version.isBlank() || "null".equals(version)) {
                continue;
            }
            versionKeys.add(versionKey(packId, version));
        }
        // 也收上游离版本文档（packs.versions 与 VERSION_COLLECTION 不同步时）
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(VERSION_COLLECTION, page, 200);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> versionDoc : batch) {
                if (packId.equals(String.valueOf(versionDoc.get("packId")))) {
                    versionKeys.add(versionKey(packId, String.valueOf(versionDoc.get("version"))));
                }
            }
            page++;
        }
        for (String key : versionKeys) {
            documents.delete(VERSION_COLLECTION, key);
            files.delete(ARCHIVE_PREFIX + key + ARCHIVE_SUFFIX);
        }
        int removedVersions = versionKeys.size();
        int unbound = clearBindingsForPack(packId);
        documents.delete(PACK_COLLECTION, packId);
        eventBus.publish(YmclEventBus.TYPE_PACK_DELETED, Map.of(
                "packId", packId, "versions", removedVersions, "unboundServers", unbound));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deleted", true);
        payload.put("packId", packId);
        payload.put("versions", removedVersions);
        payload.put("unboundServers", unbound);
        return PluginHttpResponse.json(200, payload);
    }

    /**
     * 删除单个不可变版本（管理清理）。若仍有服务器 pin 在该版本则拒绝，
     * 避免启动器拉到悬空绑定。
     */
    @PluginHttpEndpoint(method = "DELETE", path = "/v1/admin/packs/{packId}/versions/{version}",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse deleteVersion(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        String version = PathSegments.segment(request.path(), 5);
        if (packId == null || packId.isBlank() || version == null || version.isBlank()) {
            return badRequest("invalid_body", "packId and version are required");
        }
        Map<String, Object> pack = documents.findById(PACK_COLLECTION, packId).orElse(null);
        if (pack == null) {
            return badRequest(404, "pack_not_found", packId + " not found");
        }
        if (documents.findById(VERSION_COLLECTION, versionKey(packId, version)).isEmpty()) {
            return badRequest(404, "version_not_found", version + " not found");
        }
        List<String> boundServers = findServersPinnedTo(packId, version);
        if (!boundServers.isEmpty()) {
            return badRequest(409, "version_in_use",
                    "版本 " + version + " 仍绑定服务器：" + String.join(", ", boundServers));
        }
        documents.delete(VERSION_COLLECTION, versionKey(packId, version));
        files.delete(packArchiveKey(packId, version));
        List<Object> remaining = new ArrayList<>();
        for (Object item : castList(pack.get("versions"))) {
            if (item instanceof Map<?, ?> entry && !version.equals(String.valueOf(entry.get("version")))) {
                remaining.add(new LinkedHashMap<>((Map<String, Object>) entry));
            }
        }
        pack.put("versions", remaining);
        pack.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(PACK_COLLECTION, packId, pack);
        eventBus.publish(YmclEventBus.TYPE_PACK_UPDATED, Map.of(
                "packId", packId, "version", version, "removed", true));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deleted", true);
        payload.put("packId", packId);
        payload.put("version", version);
        return PluginHttpResponse.json(200, payload);
    }

    /**
     * MIP §4 撤销发布（yank，管理端）：把版本从版本列表隐藏，启动器不再选它
     * 作更新目标；已安装实例不受影响，版本清单保留（不可变），需要彻底清理
     * 走 DELETE /v1/admin/packs/{packId}/versions/{version}。
     */
    @PluginHttpEndpoint(method = "POST", path = "/mip/api/packs/{packId}/versions/{version}/yank",
            permission = YmclAdapterPlugin.PUBLISH_PERMISSION)
    public PluginHttpResponse yankVersion(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        String version = PathSegments.segment(request.path(), 5);
        if (packId == null || packId.isBlank() || version == null || version.isBlank()) {
            return badRequest("invalid_body", "packId and version are required");
        }
        Map<String, Object> pack = documents.findById(PACK_COLLECTION, packId).orElse(null);
        if (pack == null) {
            return badRequest(404, "pack_not_found", packId + " not found");
        }
        if (documents.findById(VERSION_COLLECTION, versionKey(packId, version)).isEmpty()) {
            return badRequest(404, "version_not_found", version + " not found");
        }
        List<Object> remaining = new ArrayList<>();
        boolean present = false;
        for (Object item : castList(pack.get("versions"))) {
            if (item instanceof Map<?, ?> entry && version.equals(String.valueOf(entry.get("version")))) {
                present = true;
                continue;
            }
            if (item instanceof Map<?, ?> entry) {
                remaining.add(new LinkedHashMap<>((Map<String, Object>) entry));
            }
        }
        if (present) {
            pack.put("versions", remaining);
            pack.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            documents.save(PACK_COLLECTION, packId, pack);
        }
        documents.findById(VERSION_COLLECTION, versionKey(packId, version))
                .ifPresent(doc -> {
                    doc.put("yanked", true);
                    doc.put("yankedAt", String.valueOf(System.currentTimeMillis()));
                    documents.save(VERSION_COLLECTION, versionKey(packId, version), doc);
                });
        eventBus.publish(YmclEventBus.TYPE_PACK_UPDATED, Map.of(
                "packId", packId, "version", version, "yanked", true));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("yanked", true);
        payload.put("packId", packId);
        payload.put("version", version);
        return PluginHttpResponse.json(200, payload);
    }

    /**
     * 管理端 CAS 文件预览：按扩展名分流 text / mod / image / binary。
     * path 可选，用于识别类型与展示文件名；image 直接复用公共 CAS 下发地址。
     */
    @PluginHttpEndpoint(method = "GET", path = "/v1/admin/cas/{sha512}/preview",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse previewObject(PluginHttpRequest request) {
        String sha512 = PathSegments.segment(request.path(), 3);
        if (sha512 == null || sha512.isBlank()) {
            return badRequest("invalid_body", "sha512 is required");
        }
        String path = query(request, "path");
        var stored = getOrNull(CAS_PREFIX + sha512.toLowerCase(Locale.ROOT));
        if (stored == null) {
            return badRequest(404, "object_not_found", sha512);
        }
        long size = stored.contentLength() == null ? 0L : stored.contentLength();
        String kind = CasFilePreview.kindFor(path);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sha512", sha512.toLowerCase(Locale.ROOT));
        payload.put("path", path == null ? "" : path);
        payload.put("filename", basename(path));
        payload.put("size", size);
        payload.put("kind", kind);
        payload.put("downloadUrl", "/mip/objects/" + sha512.toLowerCase(Locale.ROOT));
        try {
            if (CasFilePreview.KIND_TEXT.equals(kind)) {
                byte[] bytes = readBytes(stored.inputStream(), CasFilePreview.MAX_TEXT_BYTES + 1);
                payload.putAll(CasFilePreview.textPreview(bytes));
            } else if (CasFilePreview.KIND_MOD.equals(kind)) {
                if (size > 64L * 1024 * 1024) {
                    payload.put("kind", CasFilePreview.KIND_BINARY);
                    payload.put("message", "压缩包超过 64MB，已降级为元数据预览，请下载后查看");
                } else {
                    payload.putAll(CasFilePreview.modPreview(stored.inputStream()));
                }
            } else if (CasFilePreview.KIND_IMAGE.equals(kind)) {
                payload.put("contentType", imageContentType(CasFilePreview.extension(path)));
            } else {
                payload.put("message", "二进制文件暂不支持在线内容预览，可下载后查看");
            }
        } catch (Exception error) {
            return badRequest(500, "preview_failed", String.valueOf(error.getMessage()));
        }
        return PluginHttpResponse.json(200, payload);
    }

    private static String query(PluginHttpRequest request, String name) {
        List<String> values = request.query().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        return value == null || value.isBlank() ? null : value;
    }

    private static String basename(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String imageContentType(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "ico" -> "image/x-icon";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }

    private static byte[] readBytes(InputStream input, int max) throws IOException {
        try (input; var buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > max) {
                    break;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private int clearBindingsForPack(String packId) {
        List<String> serverIds = new ArrayList<>();
        List<Map<String, Object>> optionalBindings = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(BINDING_COLLECTION, page, 200);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> binding : batch) {
                if (packId.equals(String.valueOf(binding.get("packId")))
                        && binding.get("serverId") != null) {
                    serverIds.add(String.valueOf(binding.get("serverId")));
                } else if (packId.equals(String.valueOf(binding.get("optionalPackId")))
                        && binding.get("serverId") != null) {
                    optionalBindings.add(binding);
                }
            }
            page++;
        }
        for (String serverId : serverIds) {
            documents.delete(BINDING_COLLECTION, serverId);
            eventBus.publish(YmclEventBus.TYPE_BINDING_UPDATED, Map.of(
                    "serverId", serverId,
                    "packId", packId,
                    "cleared", true));
        }
        // 原版增强包引用：合并写的绑定文档只摘掉 optional 三字段，保留必装绑定
        for (Map<String, Object> binding : optionalBindings) {
            String serverId = String.valueOf(binding.get("serverId"));
            binding.remove("optionalPackId");
            binding.remove("optionalChannel");
            binding.remove("optionalPinnedVersion");
            binding.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            documents.save(BINDING_COLLECTION, serverId, binding);
            eventBus.publish(YmclEventBus.TYPE_BINDING_UPDATED, Map.of(
                    "serverId", serverId,
                    "packId", packId,
                    "optionalCleared", true));
        }
        return serverIds.size();
    }

    private List<String> findServersPinnedTo(String packId, String version) {
        List<String> servers = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(BINDING_COLLECTION, page, 200);
            if (batch.isEmpty()) {
                break;
            }
            for (Map<String, Object> binding : batch) {
                boolean pinned = packId.equals(String.valueOf(binding.get("packId")))
                        && version.equals(String.valueOf(binding.get("pinnedVersion")));
                boolean optionalPinned = packId.equals(String.valueOf(binding.get("optionalPackId")))
                        && version.equals(String.valueOf(binding.get("optionalPinnedVersion")));
                if (pinned || optionalPinned) {
                    servers.add(String.valueOf(binding.get("serverId")));
                }
            }
            page++;
        }
        return servers;
    }

    /** 空串 = 清空该字段；null = 保留原值。 */
    private static void putOptionalTrimmed(Map<String, Object> target, String key, Object raw) {
        if (raw == null) {
            return;
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty()) {
            target.remove(key);
            return;
        }
        if (value.length() > 512) {
            value = value.substring(0, 512);
        }
        target.put(key, value);
    }

    // ── 读路径 ──────────────────────────────────────────────────────────

    /**
     * 版本列表（MIP §4：按 releasedAt 倒序，最新在前；支持 ?channel= 过滤，
     * 已 yank 的版本不再出现）。启动器选更新目标取列表首项，顺序错误会导致
     * 新版本永远不生效。
     */
    @PluginHttpEndpoint(method = "GET", path = "/mip/api/packs/{packId}/versions",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse versions(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        Map<String, Object> pack = documents.findById(PACK_COLLECTION, packId).orElse(null);
        String channel = query(request, "channel");
        List<Map<String, Object>> versions = new ArrayList<>();
        if (pack != null) {
            for (Object item : castList(pack.get("versions"))) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) raw);
                if (channel != null && !channel.isBlank()
                        && !channel.trim().equals(String.valueOf(entry.getOrDefault("channel", "stable")))) {
                    continue;
                }
                versions.add(entry);
            }
        }
        versions.sort((left, right) -> Long.compare(releasedAtMillis(right), releasedAtMillis(left)));
        return PluginHttpResponse.rawJson(200, Map.of("versions", versions));
    }

    private static long releasedAtMillis(Map<String, Object> entry) {
        try {
            return Long.parseLong(String.valueOf(entry.get("releasedAt")));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @PluginHttpEndpoint(method = "GET", path = "/mip/api/packs/{packId}/manifest/{version}",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse manifest(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        String version = PathSegments.segment(request.path(), 5);
        Optional<Map<String, Object>> doc =
                documents.findById(VERSION_COLLECTION, versionKey(packId, version));
        if (doc.isEmpty()) {
            return badRequest(404, "version_not_found", version + " not found");
        }
        Map<String, Object> stored = castMap(doc.get().get("manifest"));
        if (stored == null) {
            return badRequest(404, "manifest_not_found", version + " has no manifest");
        }
        // 协议约定返回裸 manifest（MIP §3），外层版本文档字段（packId 驼峰、
        // releasedAt 等）不下发；CAS 源 URL 按当前请求来源重生成，不沿用发布
        // 时烘焙的 origin（跨机器玩家才能下载）。
        Map<String, Object> manifest = new LinkedHashMap<>(stored);
        rewriteCasSourceUrls(manifest, YmclCapabilitiesController.resolveOrigin(request));
        return PluginHttpResponse.rawJson(200, manifest);
    }

    /** sources[type=cas] 的 url 按当前请求 origin + 条目 sha512 重写。 */
    private static void rewriteCasSourceUrls(Map<String, Object> manifest, String origin) {
        if (manifest.get("files") == null) {
            return;
        }
        List<Map<String, Object>> files = new ArrayList<>();
        for (Map<String, Object> entry : castMapList(manifest.get("files"))) {
            Map<String, Object> copy = new LinkedHashMap<>(entry);
            String sha512 = str(copy, "sha512");
            List<Map<String, Object>> sources = new ArrayList<>();
            for (Map<String, Object> source : castMapList(copy.get("sources"))) {
                Map<String, Object> sourceCopy = new LinkedHashMap<>(source);
                if ("cas".equals(String.valueOf(sourceCopy.get("type"))) && sha512 != null) {
                    sourceCopy.put("url", origin + "/mip/objects/" + sha512.toLowerCase(Locale.ROOT));
                }
                sources.add(sourceCopy);
            }
            if (!sources.isEmpty()) {
                copy.put("sources", sources);
            }
            files.add(copy);
        }
        manifest.put("files", files);
    }

    /**
     * 首装 mrpack 下载（MIP WF-4：玩家初次拿包 = 标准 mrpack 导入）。
     * ?features=a,b 裁剪安装集（feature 为空 ∈ 安装集；feature ∈ 请求值 ∪
     * 默认开启），未声明的 feature id 拒绝。无裁剪需求且有留存原件时直接
     * 回原件字节；否则按 manifest 合成：http 引用进 modrinth.index.json，
     * CAS 条目进 overrides/，mip.json 以命中条目的精确路径再生成。
     */
    @PluginHttpEndpoint(method = "GET", path = "/mip/api/packs/{packId}/versions/{version}/mrpack",
            permission = YmclAdapterPlugin.VIEW_PERMISSION)
    public PluginHttpResponse packMrpack(PluginHttpRequest request) {
        String packId = PathSegments.segment(request.path(), 3);
        String version = PathSegments.segment(request.path(), 5);
        Optional<Map<String, Object>> doc =
                documents.findById(VERSION_COLLECTION, versionKey(packId, version));
        if (doc.isEmpty()) {
            return badRequest(404, "version_not_found", version + " not found");
        }
        Map<String, Object> manifest = castMap(doc.get().get("manifest"));
        if (manifest == null) {
            return badRequest(404, "manifest_not_found", version + " has no manifest");
        }
        Set<String> requested = new LinkedHashSet<>();
        String featuresParam = query(request, "features");
        if (featuresParam != null) {
            for (String id : featuresParam.split(",")) {
                if (!id.isBlank()) {
                    requested.add(id.trim());
                }
            }
        }
        List<Map<String, Object>> features = castMapList(manifest.get("features"));
        Set<String> declared = new HashSet<>();
        Set<String> installFeatures = new LinkedHashSet<>(requested);
        for (Map<String, Object> feature : features) {
            String id = str(feature, "id");
            if (id == null) {
                continue;
            }
            declared.add(id);
            if (Boolean.TRUE.equals(feature.get("default"))) {
                installFeatures.add(id);
            }
        }
        for (String id : requested) {
            if (!declared.contains(id)) {
                return badRequest("unknown_feature", "feature " + id + " is not declared");
            }
        }
        // 快路径：无裁剪需求（未请求 features 且 manifest 无 features）→ 原件
        if (requested.isEmpty() && features.isEmpty()) {
            var retained = getOrNull(packArchiveKey(packId, version));
            if (retained != null) {
                try (var input = retained.inputStream()) {
                    return mrpackResponse(packId, version, input.readAllBytes());
                } catch (Exception error) {
                    return badRequest(500, "archive_read_failed", String.valueOf(error.getMessage()));
                }
            }
        }
        try {
            return mrpackResponse(packId, version, composeMrpack(packId, version, manifest, installFeatures));
        } catch (IllegalArgumentException error) {
            return badRequest("invalid_pack", String.valueOf(error.getMessage()));
        } catch (Exception error) {
            return badRequest(500, "compose_failed", String.valueOf(error.getMessage()));
        }
    }

    private static PluginHttpResponse mrpackResponse(String packId, String version, byte[] bytes) {
        return new PluginHttpResponse(200,
                Map.of("Content-Disposition",
                        "attachment; filename=\"" + packId + "-" + version + ".mrpack\""),
                "application/x-modrinth-modpack+zip", bytes, false);
    }

    /**
     * 按 manifest 合成标准 mrpack：http 源条目进 index.files（sha512 必须，
     * sha1 缺失由启动器导入器下载后自验），其余条目字节进 overrides/；
     * mip.json 只保留安装集内 feature（files 写命中条目的精确路径）与非
     * managed 的 policy（同精确路径），语义与发布时 glob 等价。
     */
    private byte[] composeMrpack(
            String packId, String version, Map<String, Object> manifest, Set<String> installFeatures)
            throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> entry : castMapList(manifest.get("files"))) {
            String feature = str(entry, "feature");
            if (feature == null || installFeatures.contains(feature)) {
                entries.add(entry);
            }
        }
        Map<String, Object> game = castMap(manifest.get("game"));
        String minecraft = game == null ? null : str(game, "minecraft");
        if (minecraft == null || minecraft.isBlank()) {
            throw new IllegalArgumentException("manifest 未声明 game.minecraft，无法合成 mrpack");
        }
        Map<String, Object> dependencies = new LinkedHashMap<>();
        dependencies.put("minecraft", minecraft);
        Map<String, Object> loader = castMap(game.get("loader"));
        if (loader != null && str(loader, "type") != null && str(loader, "version") != null) {
            dependencies.put(str(loader, "type"), str(loader, "version"));
        }
        Map<String, Object> pack = documents.findById(PACK_COLLECTION, packId).orElse(null);
        String name = pack == null || str(pack, "name") == null ? packId : str(pack, "name");

        Map<String, byte[]> zipMembers = new LinkedHashMap<>();
        List<Map<String, Object>> indexFiles = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String path = str(entry, "path");
            String sha512 = str(entry, "sha512");
            if (path == null || sha512 == null) {
                continue;
            }
            List<String> downloads = new ArrayList<>();
            for (Map<String, Object> source : castMapList(entry.get("sources"))) {
                if ("http".equals(String.valueOf(source.get("type"))) && str(source, "url") != null) {
                    downloads.add(str(source, "url"));
                }
            }
            if (!downloads.isEmpty()) {
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("path", path);
                file.put("hashes", Map.of("sha512", sha512));
                file.put("downloads", downloads);
                Object size = entry.get("size");
                if (size instanceof Number number && number.longValue() > 0) {
                    file.put("fileSize", number.longValue());
                }
                indexFiles.add(file);
                continue;
            }
            var stored = files.get(CAS_PREFIX + sha512.toLowerCase(Locale.ROOT));
            if (stored == null) {
                throw new IllegalArgumentException("cas object missing for " + path);
            }
            try (var input = stored.inputStream()) {
                zipMembers.put("overrides/" + path, input.readAllBytes());
            }
        }
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("formatVersion", 1);
        index.put("game", "MINECRAFT");
        index.put("name", name);
        index.put("versionId", version);
        index.put("dependencies", dependencies);
        index.put("files", indexFiles);
        Map<String, byte[]> ordered = new LinkedHashMap<>();
        ordered.put("modrinth.index.json", jsonBytes(index));
        Map<String, Object> mipJson = composeMipJson(manifest, entries, installFeatures);
        if (mipJson != null) {
            ordered.put("mip.json", jsonBytes(mipJson));
        }
        ordered.putAll(zipMembers);
        return zip(ordered);
    }

    /** mip.json 再生成：安装集内 feature 的 files = 命中条目精确路径；policies 只输出非 managed。 */
    private static Map<String, Object> composeMipJson(
            Map<String, Object> manifest, List<Map<String, Object>> entries, Set<String> installFeatures) {
        Map<String, List<String>> featurePaths = new LinkedHashMap<>();
        Map<String, List<String>> policyPaths = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            String path = str(entry, "path");
            String feature = str(entry, "feature");
            if (feature != null) {
                featurePaths.computeIfAbsent(feature, key -> new ArrayList<>()).add(path);
            }
            String policy = str(entry, "policy");
            if (policy != null && !"managed".equals(policy)) {
                policyPaths.computeIfAbsent(policy, key -> new ArrayList<>()).add(path);
            }
        }
        List<Map<String, Object>> features = new ArrayList<>();
        for (Map<String, Object> feature : castMapList(manifest.get("features"))) {
            String id = str(feature, "id");
            List<String> paths = id == null ? null : featurePaths.get(id);
            if (id == null || !installFeatures.contains(id) || paths == null || paths.isEmpty()) {
                continue;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            if (feature.get("name") != null) {
                out.put("name", String.valueOf(feature.get("name")));
            }
            out.put("default", Boolean.TRUE.equals(feature.get("default")));
            out.put("conflicts", castList(feature.get("conflicts")).stream().map(String::valueOf).toList());
            out.put("files", paths);
            features.add(out);
        }
        List<Map<String, Object>> policies = new ArrayList<>();
        for (Map.Entry<String, List<String>> policy : policyPaths.entrySet()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("policy", policy.getKey());
            out.put("files", policy.getValue());
            policies.add(out);
        }
        if (features.isEmpty() && policies.isEmpty()) {
            return null;
        }
        Map<String, Object> mipJson = new LinkedHashMap<>();
        if (!features.isEmpty()) {
            mipJson.put("features", features);
        }
        if (!policies.isEmpty()) {
            mipJson.put("policies", policies);
        }
        return mipJson;
    }

    private static byte[] jsonBytes(Map<String, Object> value) throws IOException {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(value);
    }

    /** 写 zip（成员顺序即写入顺序，index 在前）；照 buildMrpackSnapshot 先例。 */
    private static byte[] zip(Map<String, byte[]> members) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip =
                     new java.util.zip.ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue());
                zip.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    @PluginHttpEndpoint(method = "GET", path = "/mip/objects/{sha512}")
    public PluginHttpResponse object(PluginHttpRequest request) {
        String sha512 = PathSegments.segment(request.path(), 2);
        var stored = getOrNull(CAS_PREFIX + sha512.toLowerCase());
        if (stored == null) {
            return badRequest(404, "object_not_found", sha512);
        }
        try (var input = stored.inputStream()) {
            return new PluginHttpResponse(200,
                    Map.of("Cache-Control", "public, max-age=31536000, immutable"),
                    "application/octet-stream",
                    input.readAllBytes(), false);
        } catch (Exception error) {
            return badRequest(500, "object_read_failed", String.valueOf(error.getMessage()));
        }
    }

    // ── mrpack 解析（MIP WF-1 静态校验 + 入库分流） ──────────────────────

    private static final class MrpackParsed {
        final List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> mipJson;
        List<Map<String, Object>> features;
        int externalRefs;
        /** index.dependencies.minecraft → manifest.game.minecraft（进服版本要求）。 */
        String minecraftVersion;
        /** index.dependencies 的加载器依赖 → manifest.game.loader.{type,version}。 */
        String loaderType;
        String loaderVersion;
    }

    /**
     * 解包 mrpack：overrides 入 CAS；index 中指向 Modrinth/CF 的文件只存
     * 引用（sources.http，带 index 的 sha512）；env.client=unsupported 的
     * 条目跳过（启动器是客户端，MIP WF-4 第 3 步）。
     */
    private MrpackParsed parseMrpack(byte[] zipBytes, String origin) throws IOException {
        MrpackParsed parsed = new MrpackParsed();
        Map<String, byte[]> members = unzip(zipBytes);
        byte[] indexBytes = members.get("modrinth.index.json");
        if (indexBytes == null) {
            throw new IllegalArgumentException("modrinth.index.json is missing; not a valid mrpack");
        }
        Map<String, Object> index;
        try {
            index = json(new String(indexBytes, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException("modrinth.index.json cannot be parsed");
        }

        // overrides + client-overrides → CAS（启动器是客户端）。
        for (Map.Entry<String, byte[]> member : members.entrySet()) {
            String zipPath = member.getKey();
            String prefix = zipPath.startsWith("client-overrides/") ? "client-overrides/"
                    : zipPath.startsWith("overrides/") ? "overrides/" : null;
            if (prefix == null) {
                continue;
            }
            String path = zipPath.substring(prefix.length());
            if (path.isBlank()) {
                continue;
            }
            checkUnsafePath(path);
            parsed.entries.add(casEntry(path, member.getValue(), "managed", null, origin));
        }

        // index files：分流（引用 vs 兜底入 CAS）。
        for (Object item : castList(index.get("files"))) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> file = castMap(item);
            String path = str(file, "path");
            if (path == null) {
                throw new IllegalArgumentException("index file without path");
            }
            checkUnsafePath(path);
            Map<String, Object> env = castMap(file.get("env"));
            if (env != null && "unsupported".equals(env.get("client"))) {
                continue;
            }
            List<Object> downloads = castList(file.get("downloads"));
            Map<String, Object> hashes = castMap(file.get("hashes"));
            String sha512 = hashes == null ? null : str(hashes, "sha512");
            if (downloads.isEmpty() || sha512 == null) {
                // 无 CDN 引用或缺 sha512 的 index 文件一律拒绝（不带病发布）；
                // 发布端已把无 CDN 来源的文件放进 overrides。
                throw new IllegalArgumentException("index file " + path
                        + " has no (download + sha512) reference; put it in overrides instead");
            }
            List<Map<String, Object>> sources = new ArrayList<>();
            for (Object url : downloads) {
                sources.add(Map.of("type", "http", "url", String.valueOf(url)));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", path);
            entry.put("sha512", sha512.toLowerCase());
            Object size = file.get("fileSize");
            entry.put("size", size instanceof Number number ? number.longValue() : 0);
            entry.put("policy", "managed");
            entry.put("sources", sources);
            parsed.entries.add(entry);
            parsed.externalRefs++;
        }
        parsed.mipJson = readMipJson(members);
        Map<String, Object> dependencies = castMap(index.get("dependencies"));
        if (dependencies != null) {
            parsed.minecraftVersion = str(dependencies, "minecraft");
            for (String key : LOADER_DEPENDENCY_KEYS) {
                String value = str(dependencies, key);
                if (value != null && !value.isBlank()) {
                    parsed.loaderType = key;
                    parsed.loaderVersion = value;
                    break;
                }
            }
        }
        return parsed;
    }

    private Map<String, Object> readMipJson(Map<String, byte[]> members) {
        byte[] bytes = members.get("mip.json");
        if (bytes == null) {
            return null;
        }
        try {
            return json(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException("mip.json cannot be parsed: " + error.getMessage());
        }
    }

    /**
     * mip.json → manifest 语义（MIP §3.5）：features 的 glob 把 feature 标到
     * 文件条目（每 path 至多一个 feature），并生成 manifest.features；
     * policies 覆盖条目 policy。校验（WF-1）：glob 至少匹配一个文件、
     * conflicts 引用已声明 feature，违规抛 IllegalArgumentException
     * （映射为 400 feature_conflict）。
     */
    private void applyMipJson(MrpackParsed parsed) {
        Map<String, Object> mipJson = parsed.mipJson;
        if (mipJson == null) {
            return;
        }
        List<Map<String, Object>> features = castMapList(mipJson.get("features"));
        Map<String, String> claimed = new LinkedHashMap<>();
        List<Map<String, Object>> manifestFeatures = new ArrayList<>();
        Set<String> declared = new HashSet<>();
        for (Map<String, Object> feature : features) {
            String id = str(feature, "id");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("feature without id");
            }
            declared.add(id);
            List<Pattern> patterns = globParam(feature).stream()
                    .map(glob -> globToRegex(String.valueOf(glob))).toList();
            int matched = 0;
            for (Map<String, Object> entry : parsed.entries) {
                String path = str(entry, "path");
                if (patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches())) {
                    String owner = claimed.get(path);
                    if (owner != null && !owner.equals(id)) {
                        throw new IllegalArgumentException(
                                "path " + path + " matches multiple features (" + owner + ", " + id + ")");
                    }
                    if (owner == null) {
                        claimed.put(path, id);
                        entry.put("feature", id);
                        matched++;
                    }
                }
            }
            Map<String, Object> mf = new LinkedHashMap<>();
            mf.put("id", id);
            Object name = feature.get("name");
            if (name != null) {
                mf.put("name", String.valueOf(name));
            }
            mf.put("default", Boolean.TRUE.equals(feature.get("default")));
            mf.put("conflicts", castList(feature.get("conflicts")).stream().map(String::valueOf).toList());
            manifestFeatures.add(mf);
            if (matched == 0) {
                throw new IllegalArgumentException("feature " + id + " glob matches no file");
            }
        }
        for (Map<String, Object> feature : manifestFeatures) {
            for (Object conflict : (List<?>) feature.get("conflicts")) {
                if (!declared.contains(String.valueOf(conflict))) {
                    throw new IllegalArgumentException("feature " + feature.get("id")
                            + " conflicts with undeclared feature " + conflict);
                }
            }
        }
        parsed.features = manifestFeatures;
        // policies：glob → policy 覆盖（MIP §3.5）。
        for (Map<String, Object> policy : castMapList(mipJson.get("policies"))) {
            List<Pattern> patterns = globParam(policy).stream()
                    .map(glob -> globToRegex(String.valueOf(glob))).toList();
            Object value = policy.get("policy");
            if (value == null || patterns.isEmpty()) {
                continue;
            }
            for (Map<String, Object> entry : parsed.entries) {
                String path = str(entry, "path");
                if (patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches())) {
                    entry.put("policy", String.valueOf(value));
                }
            }
        }
    }

    /** 兼容 "glob" 单值 / "globs" / "files" 数组三种写法（mip.json 用 files）。 */
    private static List<Object> globParam(Map<String, Object> map) {
        List<Object> globs = new ArrayList<>();
        Object single = map.get("glob");
        if (single != null) {
            globs.add(single);
        }
        globs.addAll(castList(map.get("globs")));
        globs.addAll(castList(map.get("files")));
        return globs;
    }

    /** 最小 glob：** 跨目录，* 单段，? 单字符，其余字面量。 */
    static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * 解包 zip 全部成员（首发量级，直接驻内存）；拒绝不安全路径。
     * 走 ZipFile（中央目录式）而非 ZipInputStream：启动器 async_zip 产出
     * ZIP64 头（尺寸字段 0xFFFFFFFF + data descriptor），流式解析器不认
     * （invalid entry size expected 4294967295）。
     */
    private static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        java.nio.file.Path temp =
                java.nio.file.Files.createTempFile("ymcl-mrpack", ".zip");
        try {
            java.nio.file.Files.write(temp, zipBytes);
            Map<String, byte[]> members = new LinkedHashMap<>();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(temp.toFile())) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName();
                    checkUnsafePath(name);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try (var input = zip.getInputStream(entry)) {
                        byte[] chunk = new byte[8192];
                        int read;
                        while ((read = input.read(chunk)) != -1) {
                            buffer.write(chunk, 0, read);
                        }
                    }
                    members.put(name, buffer.toByteArray());
                }
            }
            return members;
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    private static void checkUnsafePath(String path) {
        if (path.contains("..") || path.contains("\\") || path.startsWith("/")
                || path.contains(":")) {
            throw new IllegalArgumentException("unsafe path " + path);
        }
    }

    // ── CAS 与 manifest 装配 ────────────────────────────────────────────

    /** 字节入 CAS → 生成 manifest 条目（path/sha512/size/policy/feature/sources）。 */
    private Map<String, Object> casEntry(
            String path, byte[] bytes, String policy, String feature, String origin) {
        String sha512 = sha512(bytes);
        files.put(CAS_PREFIX + sha512, new ByteArrayInputStream(bytes),
                (long) bytes.length, "application/octet-stream");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("sha512", sha512);
        entry.put("size", bytes.length);
        entry.put("policy", policy == null ? "managed" : policy);
        if (feature != null) {
            entry.put("feature", feature);
        }
        entry.put("sources", List.of(Map.of(
                "type", "cas",
                "url", origin + "/mip/objects/" + sha512)));
        return entry;
    }

    /**
     * base manifest + delta → 新 manifest（MIP §6）：changed/added 换成新
     * CAS 条目（policy/feature 从索引透传），deleted 移除，moved 改路径并写
     * movedFrom（继承 base 条目的 feature/policy），其余沿用 base。
     */
    private Map<String, Object> composeDeltaManifest(
            Map<String, Object> baseManifest, Map<String, Object> body,
            PluginHttpRequest request, String origin) {
        Map<String, Object> files = new LinkedHashMap<>();
        Object baseFilesObj = baseManifest.get("files");
        if (baseFilesObj instanceof List<?> baseFiles) {
            for (Object item : baseFiles) {
                if (item instanceof Map<?, ?> entry && entry.get("path") != null) {
                    files.put(String.valueOf(entry.get("path")), new LinkedHashMap<>((Map<String, Object>) entry));
                }
            }
        }
        for (Object item : listParam(body, "deleted")) {
            files.remove(String.valueOf(item));
        }
        for (Object item : listParam(body, "moved")) {
            if (item instanceof Map<?, ?> move) {
                String from = String.valueOf(move.get("from"));
                String to = String.valueOf(move.get("to"));
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) files.remove(from);
                if (entry != null) {
                    entry.put("path", to);
                    entry.put("moved_from", from);
                    files.put(to, entry);
                }
            }
        }
        int partIndex = 0;
        List<Map<String, Object>> newEntries = new ArrayList<>();
        for (Object item : listParam(body, "changed")) {
            if (item instanceof Map<?, ?> change) {
                Map<String, Object> stored = storeDeltaEntry(castMap(change), request, partIndex++, origin);
                files.put(String.valueOf(change.get("path")), stored);
                newEntries.add(stored);
            }
        }
        for (Object item : listParam(body, "added")) {
            if (item instanceof Map<?, ?> added) {
                Map<String, Object> stored = storeDeltaEntry(castMap(added), request, partIndex++, origin);
                files.put(String.valueOf(added.get("path")), stored);
                newEntries.add(stored);
            }
        }
        List<Map<String, Object>> features = applyDeltaDeclarations(baseManifest, body, newEntries);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("files", new ArrayList<>(files.values()));
        if (!features.isEmpty()) {
            manifest.put("features", features);
        }
        return manifest;
    }

    /**
     * delta 的 features/policies 声明（MIP §3.5 扩展）：只作用于本轮
     * changed/added 条目——feature glob 标注（每 path 至多一个 feature，
     * 本轮声明覆盖索引里 state 透传的 feature），policy 覆盖；声明合并进
     * manifest.features（同 id 替换）。新声明（base 未出现过的 id）必须
     * 至少匹配一个新文件，redeclaration 放宽（可能只为改 default/名称）；
     * conflicts 必须引用已声明 id（base ∪ 本轮）。违规抛
     * IllegalArgumentException（400 feature_conflict）。
     */
    private List<Map<String, Object>> applyDeltaDeclarations(
            Map<String, Object> baseManifest, Map<String, Object> body,
            List<Map<String, Object>> newEntries) {
        List<Map<String, Object>> manifestFeatures = new ArrayList<>();
        Set<String> declaredIds = new HashSet<>();
        Object baseFeaturesObj = baseManifest.get("features");
        if (baseFeaturesObj instanceof List<?> baseFeatures) {
            for (Object item : baseFeatures) {
                if (item instanceof Map) {
                    Map<String, Object> feature = castMap(item);
                    manifestFeatures.add(new LinkedHashMap<>(feature));
                    declaredIds.add(str(feature, "id"));
                }
            }
        }
        List<Map<String, Object>> declared = castMapList(body.get("features"));
        Map<String, String> claimed = new LinkedHashMap<>();
        for (Map<String, Object> feature : declared) {
            String id = str(feature, "id");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("feature without id");
            }
            List<Pattern> patterns = globParam(feature).stream()
                    .map(glob -> globToRegex(String.valueOf(glob))).toList();
            int matched = 0;
            for (Map<String, Object> entry : newEntries) {
                String path = str(entry, "path");
                String owner = claimed.get(path);
                if (owner != null && owner.equals(id)) {
                    continue;
                }
                if (patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches())) {
                    if (owner != null) {
                        throw new IllegalArgumentException("path " + path
                                + " matches multiple features (" + owner + ", " + id + ")");
                    }
                    claimed.put(path, id);
                    entry.put("feature", id);
                    matched++;
                }
            }
            boolean redeclaration = declaredIds.contains(id);
            if (!redeclaration && matched == 0) {
                throw new IllegalArgumentException("feature " + id + " glob matches no new file");
            }
            manifestFeatures.removeIf(existing -> id.equals(str(existing, "id")));
            Map<String, Object> mf = new LinkedHashMap<>();
            mf.put("id", id);
            Object name = feature.get("name");
            if (name != null) {
                mf.put("name", String.valueOf(name));
            }
            mf.put("default", Boolean.TRUE.equals(feature.get("default")));
            mf.put("conflicts", castList(feature.get("conflicts")).stream().map(String::valueOf).toList());
            manifestFeatures.add(mf);
            declaredIds.add(id);
        }
        for (Map<String, Object> feature : manifestFeatures) {
            for (Object conflict : (List<?>) feature.get("conflicts")) {
                if (!declaredIds.contains(String.valueOf(conflict))) {
                    throw new IllegalArgumentException("feature " + feature.get("id")
                            + " conflicts with undeclared feature " + conflict);
                }
            }
        }
        // policies：glob → policy 覆盖（MIP §3.5，仅新条目）。
        for (Map<String, Object> policy : castMapList(body.get("policies"))) {
            List<Pattern> patterns = globParam(policy).stream()
                    .map(glob -> globToRegex(String.valueOf(glob))).toList();
            Object value = policy.get("policy");
            if (value == null || patterns.isEmpty()) {
                continue;
            }
            for (Map<String, Object> entry : newEntries) {
                String path = str(entry, "path");
                if (patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches())) {
                    entry.put("policy", String.valueOf(value));
                }
            }
        }
        return manifestFeatures;
    }

    /** delta 条目：二进制取自约定序号的 fileN part；policy/feature 从索引透传。 */
    private Map<String, Object> storeDeltaEntry(
            Map<String, Object> item, PluginHttpRequest request, int partIndex, String origin) {
        String path = str(item, "path");
        if (path == null) {
            throw new IllegalArgumentException("delta entry without path");
        }
        checkUnsafePath(path);
        PluginHttpPart part = request.parts().get("file" + partIndex);
        if (part == null) {
            throw new IllegalArgumentException("missing binary part file" + partIndex + " for " + path);
        }
        return casEntry(path, part.data(), str(item, "policy"), str(item, "feature"), origin);
    }

    private Map<String, Object> buildManifest(
            String packId, String version, String channel, String parent, List<Map<String, Object>> files) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format_version", 1);
        manifest.put("pack_id", packId);
        manifest.put("version", version);
        manifest.put("parent", parent);
        manifest.put("channel", channel);
        manifest.put("files", files);
        return manifest;
    }

    /**
     * 保存发布即投放的绑定。optional=false（缺省）：覆盖写为必装整合包绑定；
     * optional=true：合并写为原版增强包（适合需要原版的服务器，玩家进服时
     * 三选一）——只置 optionalPackId/optionalChannel/optionalPinnedVersion，
     * 保留已有 packId/mcVersion 要求。
     */
    private void saveBinding(
            String packId, String version, String channel, String serverId, String updatePolicy,
            boolean optional) {
        if (optional) {
            Map<String, Object> binding = documents.findById(BINDING_COLLECTION, serverId)
                    .map(existing -> new LinkedHashMap<String, Object>(existing))
                    .orElseGet(LinkedHashMap::new);
            binding.put("optionalPackId", packId);
            binding.put("optionalChannel", channel == null ? "stable" : channel);
            binding.put("optionalPinnedVersion", version);
            binding.put("serverId", serverId);
            binding.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            documents.save(BINDING_COLLECTION, serverId, binding);
            eventBus.publish(YmclEventBus.TYPE_BINDING_UPDATED,
                    Map.of("serverId", serverId, "packId", packId, "optional", true));
            return;
        }
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("packId", packId);
        binding.put("channel", channel);
        binding.put("pinnedVersion", version);
        binding.put("updatePolicy", updatePolicy);
        binding.put("serverId", serverId);
        binding.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        documents.save(BINDING_COLLECTION, serverId, binding);
        eventBus.publish(YmclEventBus.TYPE_BINDING_UPDATED, Map.of("serverId", serverId, "packId", packId));
    }

    private void saveVersion(String packId, String version, String channel, Map<String, Object> manifest) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("packId", packId);
        doc.put("version", version);
        doc.put("channel", channel == null ? "stable" : channel);
        doc.put("releasedAt", String.valueOf(System.currentTimeMillis()));
        doc.put("manifest", manifest);
        documents.save(VERSION_COLLECTION, versionKey(packId, version), doc);
    }

    @SuppressWarnings("unchecked")
    private void registerPackVersion(String packId, String version, String channel) {
        Map<String, Object> pack = documents.findById(PACK_COLLECTION, packId)
                .orElseGet(() -> {
                    Map<String, Object> created = new LinkedHashMap<>();
                    created.put("packId", packId);
                    created.put("versions", new ArrayList<Map<String, Object>>());
                    return created;
                });
        // 已有 pack 的 name/description/icon 等展示元数据随 findById 原样保留
        List<Object> versions = new ArrayList<>();
        Object existing = pack.get("versions");
        if (existing instanceof List<?> list) {
            versions.addAll(list);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("version", version);
        entry.put("channel", channel == null ? "stable" : channel);
        entry.put("releasedAt", String.valueOf(System.currentTimeMillis()));
        versions.add(entry);
        pack.put("versions", versions);
        documents.save(PACK_COLLECTION, packId, pack);
    }

    private Map<String, Object> ingestReport(Map<String, Object> manifest, int deltaParts) {
        Object files = manifest.get("files");
        int count = files instanceof List<?> list ? list.size() : 0;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "published");
        report.put("files", count);
        report.put("deltaParts", deltaParts);
        return report;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private static String versionKey(String packId, String version) {
        return packId + "@" + version;
    }

    private static String packArchiveKey(String packId, String version) {
        return ARCHIVE_PREFIX + versionKey(packId, version) + ARCHIVE_SUFFIX;
    }

    /** manifest.game 装配：minecraft 必填才输出，loader 依附存在。 */
    private static Map<String, Object> gameSection(
            String minecraftVersion, String loaderType, String loaderVersion) {
        Map<String, Object> game = new LinkedHashMap<>();
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            game.put("minecraft", minecraftVersion);
            if (loaderType != null && loaderVersion != null) {
                game.put("loader", Map.of("type", loaderType, "version", loaderVersion));
            }
        }
        return game;
    }

    private static String partText(PluginHttpRequest request, String name) {
        PluginHttpPart part = request.parts().get(name);
        if (part == null) {
            return null;
        }
        String text = part.text();
        return text.isBlank() ? null : text;
    }

    private Map<String, Object> bindPart(PluginHttpRequest request) {
        String bind = partText(request, "bind");
        if (bind == null) {
            return null;
        }
        try {
            return json(bind);
        } catch (Exception error) {
            throw new IllegalArgumentException("part 'bind' must be JSON");
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<Object> listParam(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return new ArrayList<Object>(list);
        }
        return List.of();
    }

    private static List<Map<String, Object>> castMapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) item;
                    result.add(cast);
                }
            }
        }
        return result;
    }

    private static List<Object> castList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<Object>(list);
        }
        return new ArrayList<>();
    }

    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private Map<String, Object> json(String body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    private static PluginHttpResponse badRequest(String code, String message) {
        return badRequest(400, code, message);
    }

    private static PluginHttpResponse badRequest(int status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", message);
        return PluginHttpResponse.rawJson(status, payload);
    }

    private static String sha512(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
