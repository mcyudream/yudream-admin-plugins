package online.yudream.plugin.voxelithmap.support;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存 {@link PluginFileStore}：测试替身。
 *
 * <p>宿主给插件的文件存储是 S3 对象存储，测试里不可能真连；这份实现保持同一套语义
 * （key → 字节 + contentType，<b>{@code get} 未命中即抛异常</b>），足够覆盖 key 布局、
 * 按代回收、发布边界这些纯逻辑。</p>
 *
 * <p>「未命中返回 {@code null}」是曾经写错过的假设：宿主 {@code ObjectStoragePluginFileStore}
 * 委托 S3，对象不存在时抛 {@code BizException("文件不存在")}。替身如果宽容地返回 {@code null}，
 * 这类只会在真实环境炸掉的问题（例如管理列表对每张地图读 {@code sidecar.json}）在单测里
 * 永远暴露不出来。所以这里刻意复刻抛异常的语义。</p>
 */
public final class InMemoryFileStore implements PluginFileStore {

    /** 一次写入的内容。 */
    public record Entry(byte[] data, String contentType) {
    }

    private final Map<String, Entry> files = new LinkedHashMap<>();

    @Override
    public String put(String key, InputStream input, long len, String contentType) {
        try {
            files.put(key, new Entry(input.readAllBytes(), contentType));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return key;
    }

    /** 对象不存在时抛出，对应宿主 S3 的 {@code NoSuchKey} → {@code BizException}。 */
    public static final class MissingObjectException extends RuntimeException {
        public MissingObjectException(String key) {
            super("文件不存在: " + key);
        }
    }

    @Override
    public PluginStoredFile get(String key) {
        Entry entry = files.get(key);
        if (entry == null) {
            throw new MissingObjectException(key);
        }
        return new PluginStoredFile(key, entry.contentType(),
                (long) entry.data().length, new ByteArrayInputStream(entry.data()));
    }

    @Override
    public void delete(String key) {
        files.remove(key);
    }

    // ---------- 断言辅助 ----------

    /** 当前全部 key（按写入顺序）。 */
    public List<String> keys() {
        return List.copyOf(files.keySet());
    }

    public boolean has(String key) {
        return files.containsKey(key);
    }

    /** 以 UTF-8 读回内容；不存在返回 {@code null}（断言辅助，不走 SPI 的 get）。 */
    public String text(String key) {
        Entry entry = files.get(key);
        return entry == null ? null : new String(entry.data(), StandardCharsets.UTF_8);
    }

    public byte[] bytes(String key) {
        Entry entry = files.get(key);
        return entry == null ? null : entry.data().clone();
    }
}
