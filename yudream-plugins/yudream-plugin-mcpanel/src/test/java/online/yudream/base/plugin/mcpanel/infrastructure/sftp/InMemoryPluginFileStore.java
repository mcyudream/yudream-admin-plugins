package online.yudream.base.plugin.mcpanel.infrastructure.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** PluginFileStore 内存替身：仅满足 host key 持久化语义。 */
final class InMemoryPluginFileStore
        implements online.yudream.base.plugin.spi.system.storage.PluginFileStore {

    private final Map<String, byte[]> objects = new HashMap<>();

    @Override
    public String put(String key, InputStream inputStream, long length, String contentType) {
        try {
            objects.put(key, inputStream.readAllBytes());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return key;
    }

    @Override
    public online.yudream.base.plugin.spi.system.storage.PluginStoredFile get(String key) {
        byte[] data = objects.get(key);
        if (data == null) {
            // 镜像宿主对象存储语义：缺失 key 抛异常而非返回 null。
            throw new IllegalStateException("文件不存在");
        }
        return new online.yudream.base.plugin.spi.system.storage.PluginStoredFile(
                key, "application/octet-stream", (long) data.length, new java.io.ByteArrayInputStream(data));
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}
