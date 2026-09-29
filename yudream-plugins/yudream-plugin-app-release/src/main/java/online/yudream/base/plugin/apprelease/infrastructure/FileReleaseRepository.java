package online.yudream.base.plugin.apprelease.infrastructure;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import online.yudream.base.plugin.apprelease.domain.AppRelease;
import online.yudream.base.plugin.apprelease.domain.ReleaseRepository;
import online.yudream.base.plugin.apprelease.domain.ReleaseSettings;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 基于宿主插件文件存储的更新发布仓储（对齐 launcher-adapter FilePackRepository 的
 * JSON 索引 + 二进制对象布局）：
 * <ul>
 *     <li>{@code meta/releases-index.json} — 全部版本元数据数组</li>
 *     <li>{@code meta/settings.json} — 通道设置</li>
 *     <li>{@code packages/{id}} — 更新包二进制</li>
 * </ul>
 */
public class FileReleaseRepository implements ReleaseRepository {

    private static final String META_INDEX = "meta/releases-index.json";
    private static final String META_SETTINGS = "meta/settings.json";
    private static final String PACKAGE_PREFIX = "packages/";

    private final PluginFileStore store;
    private final ObjectMapper mapper;

    public FileReleaseRepository(PluginFileStore store) {
        this.store = store;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    @Override
    public List<AppRelease> listAll() {
        return readIndex().stream()
                .sorted(Comparator.comparingInt(AppRelease::versionCode).reversed())
                .toList();
    }

    @Override
    public Optional<AppRelease> find(String id) {
        return readIndex().stream().filter(r -> r.id().equals(id)).findFirst();
    }

    @Override
    public void savePackage(AppRelease release, byte[] content) {
        try {
            store.put(PACKAGE_PREFIX + release.id(), new ByteArrayInputStream(content), content.length,
                    "application/vnd.android.package-archive");
        } catch (RuntimeException e) {
            throw new IllegalStateException("更新包写入失败：" + e.getMessage(), e);
        }
        upsertMeta(release);
    }

    @Override
    public void saveMeta(AppRelease release) {
        upsertMeta(release);
    }

    @Override
    public void delete(String id) {
        List<AppRelease> releases = new ArrayList<>(readIndex());
        releases.removeIf(r -> r.id().equals(id));
        writeJson(META_INDEX, releases);
        try {
            store.delete(PACKAGE_PREFIX + id);
        } catch (RuntimeException ignored) {
            // 二进制已缺失时元数据删除仍需生效
        }
    }

    @Override
    public Optional<byte[]> readPackage(String id) {
        PluginStoredFileHolder file = read(PACKAGE_PREFIX + id);
        if (file == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(file.bytes());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public ReleaseSettings settings() {
        String json = readJson(META_SETTINGS);
        if (json == null) {
            return ReleaseSettings.defaults();
        }
        try {
            return mapper.readValue(json, ReleaseSettings.class);
        } catch (IOException | RuntimeException e) {
            // 旧格式 / 空文件：视为默认，避免 500 卡死管理页
            return ReleaseSettings.defaults();
        }
    }

    @Override
    public void saveSettings(ReleaseSettings settings) {
        writeJson(META_SETTINGS, settings);
    }

    private void upsertMeta(AppRelease release) {
        List<AppRelease> releases = new ArrayList<>(readIndex());
        releases.removeIf(r -> r.id().equals(release.id()));
        releases.add(release);
        writeJson(META_INDEX, releases);
    }

    private List<AppRelease> readIndex() {
        String json = readJson(META_INDEX);
        if (json == null) {
            return List.of();
        }
        try {
            AppRelease[] arr = mapper.readValue(json, AppRelease[].class);
            return List.of(arr);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("解析 releases-index.json 失败", e);
        }
    }

    private String readJson(String key) {
        PluginStoredFileHolder file = read(key);
        return file == null ? null : file.json();
    }

    private PluginStoredFileHolder read(String key) {
        try {
            PluginStoredFile f = store.get(key);
            if (f == null) {
                return null;
            }
            byte[] bytes = f.inputStream().readAllBytes();
            return new PluginStoredFileHolder(bytes);
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private void writeJson(String key, Object value) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(value);
            store.put(key, new ByteArrayInputStream(bytes), bytes.length, "application/json");
        } catch (IOException e) {
            throw new IllegalStateException("写入失败: " + key, e);
        }
    }

    /** 简单持有者：避免把 SPI 类型泄漏进应用层判断逻辑。 */
    private record PluginStoredFileHolder(byte[] data) {
        String json() {
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        byte[] bytes() {
            return data;
        }
    }
}
