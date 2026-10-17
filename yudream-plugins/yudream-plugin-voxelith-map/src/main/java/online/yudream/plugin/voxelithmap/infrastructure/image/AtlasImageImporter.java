package online.yudream.plugin.voxelithmap.infrastructure.image;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

/**
 * 2D 地图航拍图导入：把渲染产物里的 LOD 图集页降采样成长边 ≤ {@link #MAX_EDGE} 的 JPEG。
 *
 * <p>为什么导入而不是直接用原图：图集页是给三维渲染的纹理图集，尺寸按瓦片网格对齐，
 * 大地图上单页可达数千像素宽，PNG 体积与其成正比——2D 地图是一张总览，4096px 已远超需要，
 * 体积却能降一个数量级；浏览器解码小图也快得多。</p>
 *
 * <p>取不到 AWT/ImageIO（极简 JRE 缺 java.desktop）或图片解不开时返回空，由调用方退回原图：
 * 导入失败不该让 2D 地图完全不可用。</p>
 */
public final class AtlasImageImporter {

    /** 航拍图长边上限：总览用途 4096 已足够清晰 */
    public static final int MAX_EDGE = 4096;

    private AtlasImageImporter() {
    }

    /**
     * 把图集页降采样成长边 ≤ {@code maxEdge} 的 JPEG。
     *
     * @param source  原始图集页字节（PNG）
     * @param maxEdge 长边上限（像素）
     * @return JPEG 字节；无法生成时为空
     */
    public static Optional<byte[]> jpeg(byte[] source, int maxEdge) {
        if (source == null || source.length == 0) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                return Optional.empty();
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return Optional.empty();
            }
            double scale = Math.min(1d, (double) maxEdge / Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            if (targetWidth == width && targetHeight == height) {
                // 原图不超限：仍转 JPEG（体积通常远小于 PNG）
                return writeJpeg(image);
            }
            BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            }
            finally {
                graphics.dispose();
            }
            return writeJpeg(target);
        }
        catch (RuntimeException | java.io.IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<byte[]> writeJpeg(BufferedImage image) {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            // 图集页无透明通道（底色为天空色），RGB + 白底填充即可
            if (image.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(),
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = rgb.createGraphics();
                try {
                    graphics.drawImage(image, 0, 0, Color.WHITE, null);
                }
                finally {
                    graphics.dispose();
                }
                image = rgb;
            }
            if (!ImageIO.write(image, "jpg", buffer)) {
                return Optional.empty();
            }
            return Optional.of(buffer.toByteArray());
        }
        catch (java.io.IOException e) {
            return Optional.empty();
        }
    }
}
