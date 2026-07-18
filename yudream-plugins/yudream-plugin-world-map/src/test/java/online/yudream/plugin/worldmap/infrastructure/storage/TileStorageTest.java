package online.yudream.plugin.worldmap.infrastructure.storage;

import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TileStorage key 布局与读写删除测试（内存 PluginFileStore）。 */
class TileStorageTest {

    /** 内存 PluginFileStore stub。 */
    static final class InMemoryFileStore implements PluginFileStore {
        record Entry(byte[] data, String contentType) {
        }

        final Map<String, Entry> files = new HashMap<>();

        @Override
        public String put(String key, InputStream input, long len, String contentType) {
            try {
                files.put(key, new Entry(input.readAllBytes(), contentType));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return key;
        }

        @Override
        public PluginStoredFile get(String key) {
            Entry e = files.get(key);
            return e == null ? null
                    : new PluginStoredFile(key, e.contentType(), (long) e.data().length,
                    new ByteArrayInputStream(e.data()));
        }

        @Override
        public void delete(String key) {
            files.remove(key);
        }
    }

    private final InMemoryFileStore store = new InMemoryFileStore();
    private final TileStorage tiles = new TileStorage(store);

    @Test
    void key布局符合契约() throws Exception {
        byte[] json = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
        tiles.saveHires("survival", 1, -2, json);
        assertTrue(store.files.containsKey("maps/survival/tiles/hires/1/-2.json.gz"));
        assertEquals("application/json", store.files.get("maps/survival/tiles/hires/1/-2.json.gz").contentType());

        byte[] png = {1, 2, 3};
        tiles.saveLowres("survival", 2, 0, -1, png);
        assertTrue(store.files.containsKey("maps/survival/tiles/lowres/2/0/-1.png"));

        tiles.saveAtlas("survival", png);
        assertTrue(store.files.containsKey("maps/survival/textures/atlas.png"));

        tiles.saveWorldZip("survival", png);
        assertTrue(store.files.containsKey("maps/survival/world.zip"));

        tiles.saveClientJar("survival", png);
        assertTrue(store.files.containsKey("maps/survival/client.jar"));

        // 读回
        Optional<PluginStoredFile> hires = tiles.hires("survival", 1, -2);
        assertTrue(hires.isPresent());
        assertArrayEquals(json, hires.get().inputStream().readAllBytes());
        assertTrue(tiles.lowres("survival", 2, 0, -1).isPresent());
        assertTrue(tiles.atlas("survival").isPresent());
        assertTrue(tiles.worldZip("survival").isPresent());
        assertTrue(tiles.clientJar("survival").isPresent());
        assertTrue(tiles.hires("survival", 99, 99).isEmpty(), "未写入的 tile 应为 empty");
    }

    @Test
    void 透传putGetDelete() throws Exception {
        byte[] data = "blob".getBytes(StandardCharsets.UTF_8);
        tiles.put("maps/m/raw.bin", data, "application/octet-stream");
        PluginStoredFile file = tiles.get("maps/m/raw.bin");
        assertArrayEquals(data, file.inputStream().readAllBytes());
        assertEquals("application/octet-stream", file.contentType());
        assertEquals(data.length, file.contentLength());

        assertNull(tiles.get("maps/m/none.bin"), "缺失 key 应返回 null");

        tiles.delete("maps/m/raw.bin");
        assertFalse(store.files.containsKey("maps/m/raw.bin"));
    }

    @Test
    void deleteMap删除已知单例() {
        byte[] data = {9};
        tiles.saveWorldZip("m", data);
        tiles.saveClientJar("m", data);
        tiles.saveAtlas("m", data);
        tiles.saveHires("m", 0, 0, data); // tile 不在 deleteMap 范围（由上层按清单删）

        tiles.deleteMap("m");
        assertFalse(store.files.containsKey("maps/m/world.zip"));
        assertFalse(store.files.containsKey("maps/m/client.jar"));
        assertFalse(store.files.containsKey("maps/m/textures/atlas.png"));
        assertTrue(store.files.containsKey("maps/m/tiles/hires/0/0.json.gz"),
                "tile 应由上层经 delete(key) 逐个删除");
    }
}
