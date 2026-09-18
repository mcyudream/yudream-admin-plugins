package online.yudream.base.plugin.material.application;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import online.yudream.base.plugin.material.domain.MaterialType;
import online.yudream.base.plugin.material.infrastructure.CoverImageSupport;
import online.yudream.base.plugin.material.infrastructure.MaterialFileStorage;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

/**
 * 文件落库与缩略图生成的共用实现：父物料主文件与子物料文件走同一套逻辑，
 * 只是 objectKey 布局不同（由调用方给出）。
 */
final class StoredFileWriter {
    private final MaterialFileStorage storage;

    StoredFileWriter(MaterialFileStorage storage) {
        this.storage = storage;
    }

    /** 平台文件复制进插件命名空间；长度未知时落内存（宿主平台上传本身有大小限制）。 */
    StoredPayload write(String objectKey, PluginStoredFile platform, String ext) {
        String contentType = platform.contentType() != null && !platform.contentType().isBlank()
                ? platform.contentType() : MaterialType.mimeOf(ext);
        try {
            Long length = platform.contentLength();
            if (length != null && length >= 0) {
                try (InputStream input = platform.inputStream()) {
                    storage.put(objectKey, input, length, contentType);
                }
                return new StoredPayload(objectKey, length, contentType);
            }
            byte[] bytes;
            try (InputStream input = platform.inputStream()) {
                bytes = input.readAllBytes();
            }
            storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
            return new StoredPayload(objectKey, bytes.length, contentType);
        }
        catch (Exception e) {
            throw new IllegalStateException("文件落库失败：" + e.getMessage(), e);
        }
    }

    /** 从已落库原图生成 JPEG 缩略图；不可栅格化或解码失败时返回 null，不影响原文件。 */
    String writeCover(String coverObjectKey, String ext, String sourceObjectKey) {
        if (!CoverImageSupport.rasterizable(ext)) {
            return null;
        }
        PluginStoredFile stored = storage.getOrNull(sourceObjectKey);
        if (stored == null || stored.inputStream() == null) {
            return null;
        }
        try (InputStream input = stored.inputStream()) {
            byte[] jpeg = CoverImageSupport.thumbnailJpeg(input);
            if (jpeg == null || jpeg.length == 0) {
                return null;
            }
            storage.put(coverObjectKey, new ByteArrayInputStream(jpeg), jpeg.length, CoverImageSupport.COVER_CONTENT_TYPE);
            return coverObjectKey;
        }
        catch (Exception e) {
            return null;
        }
    }

    byte[] readBytes(String objectKey) {
        PluginStoredFile stored = storage.getOrNull(objectKey);
        if (stored == null || stored.inputStream() == null) {
            throw new NotFoundException("文件对象缺失，可能已被清理");
        }
        try (InputStream input = stored.inputStream()) {
            return input.readAllBytes();
        }
        catch (Exception e) {
            throw new IllegalStateException("读取文件失败：" + e.getMessage(), e);
        }
    }

    void deleteObject(String objectKey) {
        storage.deleteQuietly(objectKey);
    }

    record StoredPayload(String objectKey, long size, String contentType) {
    }
}
