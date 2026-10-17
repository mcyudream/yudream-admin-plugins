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
 * 列表封面缩略图生成。
 *
 * <p>画廊一张卡片只有几百像素宽，却去拉长边 1280 的 PNG 截屏（无损、常见数百 KB 到数 MB）。
 * 地图一多，这些全尺寸图会同时占满浏览器同源连接（站点跑在 HTTP/1.1 上，同源仅约 6 条连接），
 * 页面其它请求连宿主的导航守卫请求都得排队——刷新时表现为「页面无响应」。这里在保存封面时
 * 顺手生成一张小尺寸 JPEG，列表只取它，单图从数 MB 降到几十 KB。</p>
 *
 * <p>取不到 AWT/ImageIO（极简 JRE 缺 java.desktop）或图片解不开时返回空，由调用方退回原图：
 * 生成不了缩略图不该让封面保存失败，更不该让列表打不开。</p>
 */
public final class CoverThumbnailer {

    /** 缩略图长边：卡片宽约 320 CSS px，2 倍屏下 640 足够清晰 */
    private static final int MAX_EDGE = 640;

    private CoverThumbnailer() {
    }

    /**
     * 把封面图缩成小尺寸 JPEG。
     *
     * @param source 原始封面字节（PNG 截屏）
     * @return JPEG 字节；无法生成时为空
     */
    public static Optional<byte[]> jpeg(byte[] source) {
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
            double scale = Math.min(1d, (double) MAX_EDGE / Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));
            // JPEG 没有 alpha 通道：截图里的透明像素按白底合成，否则会变成黑块
            BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
                graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (!ImageIO.write(target, "jpg", buffer)) {
                return Optional.empty();
            }
            return Optional.of(buffer.toByteArray());
        } catch (Throwable e) {
            // 含 NoClassDefFoundError（运行环境没有 java.desktop）：一律按「生成不了」处理
            return Optional.empty();
        }
    }
}
