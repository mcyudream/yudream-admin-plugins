package online.yudream.base.plugin.skin.interfaces.http;

import online.yudream.base.plugin.skin.application.service.YuDreamSkinAppService;
import online.yudream.base.plugin.spi.http.PluginHttpRequest;
import online.yudream.base.plugin.spi.http.PluginHttpResponse;
import online.yudream.base.plugin.spi.system.storage.PluginStoredFile;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YuDreamSkinHttpFacadeTest {

    private static final String HASH = "texture-hash";
    private static final String CACHE_CONTROL = "public, max-age=31536000";

    @Test
    void textureContentReturnsOriginalBytesAndClosesStream() throws IOException {
        byte[] bytes = skinPng();
        TrackingInputStream stream = new TrackingInputStream(bytes);

        PluginHttpResponse response = facade(stream).textureContent(request(false));

        assertPngResponse(response);
        assertArrayEquals(bytes, (byte[]) response.body());
        assertEquals(1, stream.closeCount);
    }

    @Test
    void textureContentClosesStreamWhenReadFails() {
        TrackingInputStream stream = new TrackingInputStream(new byte[0]);
        stream.failRead = true;

        PluginHttpResponse response = facade(stream).textureContent(request(false));

        assertError(response, 500, "材质读取失败：read failed");
        assertEquals(1, stream.closeCount);
    }

    @Test
    void textureContentReturnsReadErrorWhenReadAndCloseBothFail() {
        TrackingInputStream stream = new TrackingInputStream(new byte[0]);
        stream.failRead = true;
        stream.failClose = true;

        PluginHttpResponse response = facade(stream).textureContent(request(false));

        assertError(response, 500, "材质读取失败：read failed");
        assertEquals(1, stream.closeCount);
    }

    @Test
    void textureContentReturnsErrorWhenCloseFails() {
        TrackingInputStream stream = new TrackingInputStream(new byte[]{1, 2, 3});
        stream.failClose = true;

        PluginHttpResponse response = facade(stream).textureContent(request(false));

        assertError(response, 500, "材质读取失败：close failed");
        assertEquals(1, stream.closeCount);
    }

    @Test
    void textureContentPreservesEmptyContentAndClosesStream() {
        TrackingInputStream stream = new TrackingInputStream(new byte[0]);

        PluginHttpResponse response = facade(stream).textureContent(request(false));

        assertPngResponse(response);
        assertArrayEquals(new byte[0], (byte[]) response.body());
        assertEquals(1, stream.closeCount);
    }

    @Test
    void textureContentReturnsNotFoundForMissingTexture() {
        PluginHttpResponse response = facade(null).textureContent(request(false));

        assertError(response, 404, "材质文件不存在");
    }

    @Test
    void textureRenderReturnsPngAtRequestedHeightAndClosesStream() throws IOException {
        TrackingInputStream stream = new TrackingInputStream(skinPng());

        PluginHttpResponse response = facade(stream).textureRender(request(true));

        assertPngResponse(response);
        assertEquals(1, stream.closeCount);
        try (var input = new ByteArrayInputStream((byte[]) response.body())) {
            BufferedImage image = ImageIO.read(input);
            assertNotNull(image);
            assertEquals(64, image.getWidth());
            assertEquals(128, image.getHeight());
            assertEquals(0xFF_E6194B, image.getRGB(32, 16));
        }
    }

    @Test
    void textureRenderRejectsEmptyImageAndClosesStream() {
        TrackingInputStream stream = new TrackingInputStream(new byte[0]);

        PluginHttpResponse response = facade(stream).textureRender(request(true));

        assertError(response, 400, "不是有效的皮肤材质图片");
        assertEquals(1, stream.closeCount);
    }

    @Test
    void textureRenderRejectsInvalidImageAndClosesStream() {
        TrackingInputStream stream = new TrackingInputStream("not a png".getBytes(StandardCharsets.UTF_8));

        PluginHttpResponse response = facade(stream).textureRender(request(true));

        assertError(response, 400, "不是有效的皮肤材质图片");
        assertEquals(1, stream.closeCount);
    }

    @Test
    void textureRenderReturnsErrorWhenCloseFails() throws IOException {
        TrackingInputStream stream = new TrackingInputStream(skinPng());
        stream.failClose = true;

        PluginHttpResponse response = facade(stream).textureRender(request(true));

        assertError(response, 500, "材质读取失败：close failed");
        assertEquals(1, stream.closeCount);
    }

    @Test
    void textureRenderReturnsNotFoundForMissingTexture() {
        PluginHttpResponse response = facade(null).textureRender(request(true));

        assertError(response, 404, "材质文件不存在");
    }

    private static YuDreamSkinHttpFacade facade(InputStream stream) {
        YuDreamSkinAppService service = new YuDreamSkinAppService(null, null) {
            @Override
            public Optional<PluginStoredFile> readTexture(String hash) {
                assertEquals(HASH, hash);
                return stream == null ? Optional.empty()
                        : Optional.of(new PluginStoredFile(hash, "image/png", null, stream));
            }
        };
        return new YuDreamSkinHttpFacade(service, null);
    }

    private static PluginHttpRequest request(boolean render) {
        return new PluginHttpRequest("GET", "/textures/" + HASH + (render ? "/render" : ""),
                Map.of(), render ? Map.of("height", List.of("128")) : Map.of(), "", null);
    }

    private static void assertPngResponse(PluginHttpResponse response) {
        assertEquals(200, response.status());
        assertEquals("image/png", response.contentType());
        assertEquals(Map.of("Cache-Control", CACHE_CONTROL), response.headers());
        assertFalse(response.wrapped());
        assertTrue(response.body() instanceof byte[]);
    }

    private static void assertError(PluginHttpResponse response, int status, String message) {
        assertEquals(status, response.status());
        assertEquals(Map.of("message", message), response.body());
        assertFalse(response.wrapped());
        assertFalse(response.headers().containsKey("Cache-Control"));
    }

    private static byte[] skinPng() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 16; y++) {
            for (int x = 8; x < 16; x++) {
                image.setRGB(x, y, 0xFF_E6194B);
            }
        }
        try (var output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final ByteArrayInputStream delegate;
        private int closeCount;
        private boolean failRead;
        private boolean failClose;

        private TrackingInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() throws IOException {
            if (failRead) {
                throw new IOException("read failed");
            }
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (failRead) {
                throw new IOException("read failed");
            }
            return delegate.read(bytes, offset, length);
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            delegate.close();
            if (failClose) {
                throw new IOException("close failed");
            }
        }
    }
}
