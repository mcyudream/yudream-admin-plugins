package online.yudream.base.plugin.eduverify.infrastructure;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.InputStream;

public final class MaterialFileStorage {

    private final PluginFileStore files;

    public MaterialFileStorage(PluginFileStore files) {
        this.files = files;
    }

    public String objectKey(String verificationId, int index, String filename) {
        String safe = filename == null || filename.isBlank() ? "file" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "materials/" + verificationId + "/" + index + "-" + safe;
    }

    public String put(String objectKey, InputStream input, long contentLength, String contentType) {
        return files.put(objectKey, input, contentLength, contentType);
    }

    public PluginStoredFile getOrNull(String objectKey) {
        try {
            return files.get(objectKey);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void deleteQuietly(String objectKey) {
        try {
            files.delete(objectKey);
        } catch (RuntimeException ignored) {
        }
    }
}
