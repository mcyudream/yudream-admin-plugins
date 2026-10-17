package online.yudream.plugin.voxelithmap.infrastructure.engine;

import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 把宿主平台文件（上传的存档 zip、原版 client jar）落到本地磁盘。
 *
 * <p>Voxelith 渲染与预览都是**按路径读文件**的（{@code --world-dir} / {@code --packs}），
 * 而宿主 SPI 只给字节流，因此这一步是必需的桥。宿主没有「插件私有数据目录」，
 * 目标一律用调用方给的临时目录（任务结束即删），避免在宿主盘上留垃圾。</p>
 */
public final class PlatformFileMaterializer {

    private final FrameworkServices framework;
    /** 插件自有文件存储兜底：实例导入的存档 key 不在平台文件里，从这里读。 */
    private final PluginFileStore fileStore;

    public PlatformFileMaterializer(FrameworkServices framework, PluginFileStore fileStore) {
        this.framework = framework;
        this.fileStore = fileStore;
    }

    /**
     * 取平台文件并写入目标路径。
     *
     * @param fileId       平台文件 id
     * @param destination  目标文件路径
     * @param what         展示名（错误信息用）
     * @return 目标路径
     * @throws IOException 文件不存在或写盘失败
     */
    public Path materialize(String fileId, Path destination, String what) throws IOException {
        if (fileId == null || fileId.isBlank()) {
            throw new IOException(what + "未指定（缺少文件 id）");
        }
        PluginStoredFile stored = resolve(fileId)
                .orElseThrow(() -> new IOException("找不到" + what + "（文件 " + fileId + " 不存在或已删除）"));
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        Files.createDirectories(destination.getParent());
        try (InputStream input = stored.inputStream()) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        return destination;
    }

    /** 平台文件优先，插件自有文件存储兜底（实例导入的世界 zip）。 */
    private Optional<PluginStoredFile> resolve(String fileId) {
        Optional<PluginStoredFile> platform = framework.platformFile(fileId);
        if (platform.isPresent()) {
            return platform;
        }
        return fileStore == null ? Optional.empty() : Optional.ofNullable(fileStore.get(fileId));
    }

    /**
     * 按 id 取文件（平台文件优先，插件存储兜底），供下载等需要读字节的场景使用。
     *
     * @param fileId 平台文件 id 或插件存储 key
     * @return 命中则返回文件句柄；否则空
     */
    public Optional<PluginStoredFile> find(String fileId) {
        return fileId == null || fileId.isBlank() ? Optional.empty() : resolve(fileId);
    }

    /** 平台文件是否存在（用于创建项目时先校验，避免建完才发现存档没了）。 */
    public boolean exists(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return false;
        }
        Optional<PluginStoredFile> file = resolve(fileId);
        if (file.isEmpty()) {
            return false;
        }
        try (InputStream input = file.get().inputStream()) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 文件大小（未知返回 0）。
     *
     * <p>与 {@link #materialize} 同一套解析顺序：平台文件优先，插件自有存储兜底——实例导入的
     * 存档由服务端直接写进插件存储，只查平台文件会让「存档过小」这类早失败静默失效。</p>
     */
    public long sizeOf(String fileId) {
        return resolve(fileId)
                .map(PluginStoredFile::contentLength)
                .filter(length -> length != null && length > 0)
                .orElse(0L);
    }
}
