package online.yudream.base.plugin.material.infrastructure;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverImageSupportTest {

    @Test
    void rasterizableAcceptsCommonPhotoFormatsOnly() {
        assertTrue(CoverImageSupport.rasterizable("png"));
        assertTrue(CoverImageSupport.rasterizable("JPG"));
        assertTrue(CoverImageSupport.rasterizable("webp"));
        assertFalse(CoverImageSupport.rasterizable("svg"));
        assertFalse(CoverImageSupport.rasterizable("ico"));
        assertFalse(CoverImageSupport.rasterizable("psd"));
        assertFalse(CoverImageSupport.rasterizable(null));
    }

    @Test
    void thumbnailJpegShrinksLargePng() throws Exception {
        byte[] png = samplePng(1200, 800, Color.RED);
        byte[] jpeg = CoverImageSupport.thumbnailJpeg(new ByteArrayInputStream(png));
        assertNotNull(jpeg);
        assertTrue(jpeg.length < png.length);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(decoded);
        assertTrue(decoded.getWidth() <= CoverImageSupport.MAX_EDGE);
        assertTrue(decoded.getHeight() <= CoverImageSupport.MAX_EDGE);
        assertTrue(decoded.getWidth() >= CoverImageSupport.MAX_EDGE - 1
                || decoded.getHeight() >= CoverImageSupport.MAX_EDGE - 1);
    }

    @Test
    void thumbnailJpegRejectsGarbage() {
        assertNull(CoverImageSupport.thumbnailJpeg(new ByteArrayInputStream("not-an-image".getBytes(StandardCharsets.UTF_8))));
        assertNull(CoverImageSupport.thumbnailJpeg(null));
    }

    private static byte[] samplePng(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }
}
