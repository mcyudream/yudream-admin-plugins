package online.yudream.base.plugin.material.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

/** 内存文件存储假实现。 */
public final class FakeFileStore implements PluginFileStore {
    private record Entry(byte[] bytes, String contentType) {
    }

    private final Map<String, Entry> objects = new ConcurrentHashMap<>();

    public String put(String objectKey, InputStream inputStream, long contentLength, String contentType) {
        try {
            objects.put(objectKey, new Entry(inputStream.readAllBytes(), contentType));
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return objectKey;
    }

    public PluginStoredFile get(String objectKey) {
        Entry entry = objects.get(objectKey);
        if (entry == null) {
            return null;
        }
        return new PluginStoredFile(objectKey, entry.contentType(), (long) entry.bytes().length,
                new ByteArrayInputStream(entry.bytes()));
    }

    public void delete(String objectKey) {
        objects.remove(objectKey);
    }

    public boolean exists(String objectKey) {
        return objects.containsKey(objectKey);
    }
}
