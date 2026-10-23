package online.yudream.base.plugin.ymcl.application.service;

import online.yudream.base.plugin.ymcl.api.YmclPackCatalogService;
import online.yudream.base.plugin.ymcl.api.YmclContributionProvider;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.storage.PluginDocumentStore;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MIP 整合包目录聚合（YmclPackCatalogService 实现）：
 * packs × versions（每 pack 取最新 releasedAt 且未 yank）× bindings
 * （必装绑定 packId 才进目录；optionalPackId 增强包不进）。
 * 整包 sha512 现场从归档计算并缓存于版本文档（archiveSha512）。
 */
public class YmclPackCatalogServiceImpl implements YmclPackCatalogService {

    private static final String PACKS = "ymcl_packs";
    private static final String VERSIONS = "ymcl_pack_versions";
    private static final String BINDINGS = "ymcl_bindings";

    private final PluginContext context;
    private final PluginDocumentStore documents;
    private final PluginFileStore files;

    public YmclPackCatalogServiceImpl(PluginContext context, PluginDocumentStore documents,
                                      PluginFileStore files) {
        this.context = context;
        this.documents = documents;
        this.files = files;
    }

    @Override
    public List<Map<String, Object>> catalog() {
        Map<String, Map<String, Object>> packs = scan(PACKS);
        Map<String, Map<String, Object>> versions = scan(VERSIONS);

        // 每 pack 最新发布版本（releasedAt 最大、未 yank）
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> version : versions.values()) {
            if (num(version.get("yanked")) > 0) {
                continue;
            }
            String packId = text(version.get("packId"));
            if (packId == null) {
                continue;
            }
            Map<String, Object> current = latest.get(packId);
            if (current == null || num(version.get("releasedAt")) > num(current.get("releasedAt"))) {
                latest.put(packId, version);
            }
        }

        List<Map<String, Object>> catalog = new ArrayList<>();
        for (Map<String, Object> binding : documents.findAll(BINDINGS, 1, 200)) {
            String packId = text(binding.get("packId"));
            String serverId = text(binding.get("serverId"));
            if (packId == null || serverId == null) {
                continue;
            }
            Map<String, Object> version = resolveVersion(latest, versions, packId,
                    text(binding.get("pinnedVersion")));
            if (version == null) {
                continue;
            }
            catalog.add(entry(packs.get(packId), binding, version));
        }
        return catalog;
    }

    private Map<String, Object> entry(Map<String, Object> pack, Map<String, Object> binding,
                                      Map<String, Object> version) {
        String packId = text(version.get("packId"));
        String versionName = text(version.get("version"));
        Map<String, Object> manifest = cast(version.get("manifest"));
        Map<String, Object> game = manifest == null ? null : cast(manifest.get("game"));
        Map<String, Object> loader = game == null ? null : cast(game.get("loader"));

        String sha512 = resolveArchiveSha512(version, packId, versionName);

        Long sizeBytes = null;
        PluginStoredFile archive = files.get("packs/" + packId + "@" + versionName + ".mrpack");
        if (archive != null && archive.contentLength() != null) {
            sizeBytes = archive.contentLength();
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("packId", packId);
        entry.put("packName", pack == null ? null : text(pack.get("name")));
        entry.put("version", versionName);
        entry.put("channel", text(version.get("channel")));
        entry.put("releasedAt", text(version.get("releasedAt")));
        entry.put("gameVersion", game == null ? null : text(game.get("minecraft")));
        entry.put("loaderType", loader == null ? null : text(loader.get("type")));
        entry.put("loaderVersion", loader == null ? null : text(loader.get("version")));
        entry.put("sha512", sha512);
        entry.put("sizeBytes", sizeBytes);
        entry.put("mrpackPath", "/mip/api/packs/" + packId + "/versions/" + versionName + "/mrpack");
        entry.put("serverId", text(binding.get("serverId")));
        entry.put("serverName", serverName(text(binding.get("serverId"))));
        entry.put("serverAddress", serverAddress(text(binding.get("serverId"))));
        return entry;
    }

    /** pinnedVersion 优先；否则取该 pack 最新版。 */
    private Map<String, Object> resolveVersion(Map<String, Map<String, Object>> latest,
                                               Map<String, Map<String, Object>> versions,
                                               String packId, String pinnedVersion) {
        if (pinnedVersion != null && !pinnedVersion.isBlank()) {
            return versions.get(packId + "@" + pinnedVersion);
        }
        return latest.get(packId);
    }

    /** 整包 sha512：优先读版本文档缓存，未缓存则现场计算并回写。 */
    private String resolveArchiveSha512(Map<String, Object> version, String packId, String versionName) {
        String cached = text(version.get("archiveSha512"));
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        String key = "packs/" + packId + "@" + versionName + ".mrpack";
        PluginStoredFile stored = files.get(key);
        if (stored == null) {
            return null;
        }
        try (InputStream in = stored.inputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            String sha512 = HexFormat.of().formatHex(digest.digest());
            Map<String, Object> update = new LinkedHashMap<>(version);
            update.put("archiveSha512", sha512);
            documents.save(VERSIONS, packId + "@" + versionName, update);
            return sha512;
        } catch (Exception e) {
            return null;
        }
    }

    /** 服务器名/地址来自 minecraft-server 贡献的 serverBindings 视图。 */
    private Map<String, Object> serverView(String serverId) {
        for (YmclContributionProvider provider : context.extensions(YmclContributionProvider.class)) {
            Object bindings = provider.serverBindings();
            List<?> groups = bindings instanceof List<?> items ? items : List.of(bindings);
            for (Object group : groups) {
                List<?> list = group instanceof List<?> items ? items : List.of(group);
                for (Object item : list) {
                    if (item instanceof Map<?, ?> view
                            && serverId != null
                            && serverId.equals(String.valueOf(view.get("serverId")))) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        view.forEach((key, value) -> copy.put(String.valueOf(key), value));
                        return copy;
                    }
                }
            }
        }
        return Map.of();
    }

    private String serverName(String serverId) {
        Object name = serverView(serverId).get("name");
        return name == null ? null : String.valueOf(name);
    }

    private String serverAddress(String serverId) {
        Object address = serverView(serverId).get("mcAddress");
        return address == null ? null : String.valueOf(address);
    }

    private Map<String, Map<String, Object>> scan(String collection) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = documents.findAll(collection, page, 200);
            for (Map<String, Object> document : batch) {
                String id = text(document.get("id"));
                if (id != null) {
                    result.put(id, document);
                }
            }
            if (batch.size() < 200) {
                return result;
            }
            page++;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : parse(value);
    }

    private static long parse(Object value) {
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

}
