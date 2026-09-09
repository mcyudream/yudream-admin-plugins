package online.yudream.base.plugin.material.infrastructure;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * 库页封面缩略图：把光栅图缩小成 JPEG，避免浏览器并发拉取原图占满连接。
 * SVG/ICO/AVIF 等 ImageIO 不稳定的格式直接跳过，前端回退类型图标。
 */
public final class CoverImageSupport {
    public static final int MAX_EDGE = 400;
    public static final String COVER_CONTENT_TYPE = "image/jpeg";
    public static final float JPEG_QUALITY = 0.82f;

    private static final Set<String> RASTERIZABLE = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff");

    private CoverImageSupport() {
    }

    public static boolean rasterizable(String ext) {
        if (ext == null || ext.isBlank()) {
            return false;
        }
        return RASTERIZABLE.contains(ext.toLowerCase(Locale.ROOT));
    }

    /** 解码失败、无对应 ImageReader 或空图时返回 null，调用方应跳过封面。 */
    public static byte[] thumbnailJpeg(InputStream input) {
        if (input == null) {
            return null;
        }
        ImageReader reader = null;
        try (ImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                return null;
            }
            reader = readers.next();
            reader.setInput(imageInput, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0) {
                return null;
            }
            ImageReadParam readParam = reader.getDefaultReadParam();
            int subsample = Math.max(1, (int) Math.ceil((double) Math.max(width, height) / MAX_EDGE));
            if (subsample > 1) {
                readParam.setSourceSubsampling(subsample, subsample, 0, 0);
            }
            BufferedImage source = reader.read(0, readParam);
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return null;
            }
            BufferedImage fitted = fitRgb(source, MAX_EDGE);
            return encodeJpeg(fitted);
        }
        catch (Exception e) {
            return null;
        }
        finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    private static BufferedImage fitRgb(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, (double) maxEdge / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage rgb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        }
        finally {
            graphics.dispose();
            if (source != rgb) {
                source.flush();
            }
        }
        return rgb;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
            output.flush();
        }
        finally {
            writer.dispose();
            image.flush();
        }
        byte[] bytes = buffer.toByteArray();
        return bytes.length == 0 ? null : bytes;
    }
}
