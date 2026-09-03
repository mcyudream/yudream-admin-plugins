package online.yudream.base.plugin.material.infrastructure;

import java.io.InputStream;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

/** 插件文件存储封装：objectKey 不可变，按 materials/{materialId}/v{version}/file 布局。 */
public final class MaterialFileStorage {
    private final PluginFileStore files;

    public MaterialFileStorage(PluginFileStore files) {
        this.files = files;
    }

    public String objectKey(String materialId, int version) {
        return "materials/" + materialId + "/v" + version + "/file";
    }

    public String put(String objectKey, InputStream input, long contentLength, String contentType) {
        return files.put(objectKey, input, contentLength, contentType);
    }

    /** 缺失时返回 null（宿主实现对缺失对象可能返回 null 或抛异常，统一归一）。 */
    public PluginStoredFile getOrNull(String objectKey) {
        try {
            return files.get(objectKey);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    public void deleteQuietly(String objectKey) {
        try {
            files.delete(objectKey);
        }
        catch (RuntimeException ignored) {
            // 对象已不存在或底层存储不可用：删除物料/版本不因此失败
        }
    }
}
