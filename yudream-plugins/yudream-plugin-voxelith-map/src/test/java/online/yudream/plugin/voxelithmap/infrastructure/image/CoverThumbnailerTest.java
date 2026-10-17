package online.yudream.plugin.voxelithmap.infrastructure.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CoverThumbnailer}：列表封面缩略图生成。
 *
 * <p>封面截屏常见数百 KB 到数 MB，列表只该取小图；生成不了时必须安静地返回空，
 * 让调用方退回原图，不能抛出去把封面保存或列表加载带崩。</p>
 */
class CoverThumbnailerTest {

    private static byte[] pngOf(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(30, 120, 200));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }

    /** 大图按长边 640 缩小，且宽高比不变（列表卡片是 16:9，比例必须保持）。 */
    @Test
    void downscalesToMaxEdgeKeepingAspect() throws Exception {
        Optional<byte[]> thumb = CoverThumbnailer.jpeg(pngOf(1280, 720));
        assertTrue(thumb.isPresent(), "正常 PNG 应该能生成缩略图");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumb.get()));
        assertNotNull(decoded);
        assertEquals(640, decoded.getWidth());
        assertEquals(360, decoded.getHeight());
    }

    /** 比上限还小的图不放大（放大只是白白变模糊并增大体积）。 */
    @Test
    void keepsSmallImagesUntouchedInSize() throws Exception {
        Optional<byte[]> thumb = CoverThumbnailer.jpeg(pngOf(320, 180));
        assertTrue(thumb.isPresent());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumb.get()));
        assertNotNull(decoded);
        assertEquals(320, decoded.getWidth());
        assertEquals(180, decoded.getHeight());
    }

    /** 竖图按长边约束（不能只看宽，否则竖屏截图会超大）。 */
    @Test
    void constrainsPortraitByHeight() throws Exception {
        Optional<byte[]> thumb = CoverThumbnailer.jpeg(pngOf(720, 1280));
        assertTrue(thumb.isPresent());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumb.get()));
        assertNotNull(decoded);
        assertEquals(360, decoded.getWidth());
        assertEquals(640, decoded.getHeight());
    }

    /** 不是图片的字节：返回空而不是抛异常（误传/损坏的封面不该把保存流程炸掉）。 */
    @Test
    void returnsEmptyForNonImageBytes() {
        assertTrue(CoverThumbnailer.jpeg("definitely not an image".getBytes()).isEmpty());
    }

    /** 空输入返回空（调用方不必先判空）。 */
    @Test
    void returnsEmptyForEmptyInput() {
        assertTrue(CoverThumbnailer.jpeg(new byte[0]).isEmpty());
        assertTrue(CoverThumbnailer.jpeg(null).isEmpty());
    }
}
