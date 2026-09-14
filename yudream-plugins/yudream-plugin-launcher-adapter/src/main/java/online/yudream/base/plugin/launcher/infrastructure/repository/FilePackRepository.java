package online.yudream.base.plugin.launcher.infrastructure.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPack;
import online.yudream.base.plugin.launcher.domain.aggregate.LauncherPackVersion;
import online.yudream.base.plugin.launcher.domain.repo.PackRepository;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于宿主插件文件存储的 pack 元数据持久化。
 * <p>
 * 存储布局（objectKey 前缀由文件存储自带 namespace）：
 * <ul>
 *     <li>{@code meta/packs-index.json} — 全部 pack 元数据数组</li>
 *     <li>{@code meta/versions/{packId}/{versionId}.json} — 版本元数据</li>
 *     <li>{@code meta/versions/{packId}/_index.json} — 该 pack 的 versionId 列表</li>
 * </ul>
 */
public class FilePackRepository implements PackRepository {

    private static final String META_PACKS_INDEX = "meta/packs-index.json";
    private static final String META_VERSIONS_PREFIX = "meta/versions/";
    private static final String META_VERSIONS_SUFFIX = ".json";

    private final PluginFileStore store;
    private final ObjectMapper mapper;

    public FilePackRepository(PluginFileStore store) {
        this.store = store;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public List<LauncherPack> listPacks() {
        return loadPacksIndex();
    }

    @Override
    public Optional<LauncherPack> findPack(String packId) {
        return loadPacksIndex().stream().filter(p -> p.id().equals(packId)).findFirst();
    }

    @Override
    public void savePack(LauncherPack pack) {
        List<LauncherPack> packs = new ArrayList<>(loadPacksIndex());
        packs.removeIf(p -> p.id().equals(pack.id()));
        packs.add(pack);
        writeJson(META_PACKS_INDEX, packs);
    }

    @Override
    public List<LauncherPackVersion> listVersions(String packId) {
        return readVersionsByPack(packId);
    }

    @Override
    public Optional<LauncherPackVersion> findVersion(String packId, String versionId) {
        Optional<PluginStoredFile> file = readFile(versionMetaKey(packId, versionId));
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.get().inputStream().readAllBytes(), LauncherPackVersion.class));
        } catch (IOException e) {
            throw new RuntimeException("解析版本元数据失败: " + packId + "@" + versionId, e);
        }
    }

    @Override
    public void saveVersion(LauncherPackVersion version) {
        List<String> ids = new ArrayList<>(readVersionIds(version.packId()));
        writeJson(versionMetaKey(version.packId(), version.versionId()), version);
        if (!ids.contains(version.versionId())) {
            ids.add(version.versionId());
            writeJson(versionIndexKey(version.packId()), ids);
        }
    }

    private List<LauncherPack> loadPacksIndex() {
        Optional<PluginStoredFile> file = readFile(META_PACKS_INDEX);
        if (file.isEmpty()) {
            return List.of();
        }
        try {
            LauncherPack[] arr = mapper.readValue(file.get().inputStream().readAllBytes(), LauncherPack[].class);
            return List.of(arr);
        } catch (IOException e) {
            throw new RuntimeException("解析 packs-index.json 失败", e);
        }
    }

    private List<String> readVersionIds(String packId) {
        Optional<PluginStoredFile> file = readFile(versionIndexKey(packId));
        if (file.isEmpty()) {
            return List.of();
        }
        try {
            String[] ids = mapper.readValue(file.get().inputStream().readAllBytes(), String[].class);
            return List.of(ids);
        } catch (IOException e) {
            throw new RuntimeException("解析版本索引失败: " + packId, e);
        }
    }

    private List<LauncherPackVersion> readVersionsByPack(String packId) {
        List<LauncherPackVersion> versions = new ArrayList<>();
        for (String id : readVersionIds(packId)) {
            findVersion(packId, id).ifPresent(versions::add);
        }
        return versions;
    }

    private String versionMetaKey(String packId, String versionId) {
        return META_VERSIONS_PREFIX + packId + "/" + versionId + META_VERSIONS_SUFFIX;
    }

    private String versionIndexKey(String packId) {
        return META_VERSIONS_PREFIX + packId + "/_index.json";
    }

    private Optional<PluginStoredFile> readFile(String key) {
        try {
            PluginStoredFile f = store.get(key);
            return Optional.ofNullable(f);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void writeJson(String key, Object value) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            store.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/json");
        } catch (IOException e) {
            throw new RuntimeException("写入失败: " + key, e);
        }
    }
}
