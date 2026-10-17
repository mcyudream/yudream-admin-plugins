package online.yudream.plugin.voxelithmap.infrastructure.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航拍图导入（2D 地图取图首选通道）的降采样与 JPEG 输出。
 *
 * <p>图集页可能非常巨大（数千像素宽），导入失败或内存失控都是事故；
 * 长边上限与「无需缩小时也转 JPEG」两条行为在这里钉住。</p>
 */
class AtlasImageImporterTest {

    /** 生成一张 width×height 的不透明测试 PNG。 */
    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, ((x * 7 + y * 13) & 0xFF) << 16 | ((x * 3 + y) & 0xFF) << 8 | 0x66);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    private static int[] decodeSize(byte[] jpeg) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        return new int[]{image.getWidth(), image.getHeight()};
    }

    @Test
    void 超长边图片被降采样到上限() throws Exception {
        byte[] jpeg = AtlasImageImporter.jpeg(png(3200, 1600), 800).orElseThrow();
        int[] size = decodeSize(jpeg);
        assertEquals(800, size[0]);
        assertEquals(400, size[1]);
    }

    @Test
    void 未超长边图片保持原尺寸但转为JPEG() throws Exception {
        byte[] jpeg = AtlasImageImporter.jpeg(png(640, 320), 4096).orElseThrow();
        int[] size = decodeSize(jpeg);
        assertEquals(640, size[0]);
        assertEquals(320, size[1]);
        // JPEG 魔数（FFD8FF）
        assertTrue((jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8);
    }

    @Test
    void 非法输入返回空而不是抛出() throws Exception {
        assertTrue(AtlasImageImporter.jpeg(new byte[0], 4096).isEmpty());
        assertTrue(AtlasImageImporter.jpeg("不是图片".getBytes(), 4096).isEmpty());
    }
}
