package online.yudream.plugin.worldmap.infrastructure.resource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 贴图集：把全部被引用的纹理拼进单张 PNG。
 *
 * <p>布局为均匀网格：每格尺寸为 16 的整数倍（取最大纹理向上取整），
 * 每格外扩 1px padding 并复制边缘像素防线性采样出血。
 * 动画贴图（宽==帧边长、高为宽整数倍的长条，如水/熔岩/火焰）只取首帧。</p>
 *
 * <p>用法：{@link #add(String, BufferedImage)} 收集纹理 → 查询时自动 {@link #build()}。</p>
 */
public final class TextureAtlas {

    /** 单格基础边长（原版纹理 16px）。 */
    public static final int BASE_CELL = 16;
    /** 每格四周边距（防出血）。 */
    public static final int PADDING = 1;
    /** 缺失纹理在贴图集中的保留名。 */
    public static final String MISSING_TEXTURE = "__missing__";

    /** 纹理在贴图集中的 uv 矩形（[0,1] 空间，不含 padding）。 */
    public record UVRect(float u0, float v0, float u1, float v1) {
        /** 把 0..16 模型 uv 映射进本矩形。 */
        public float mapU(float modelU) {
            return u0 + (modelU / 16f) * (u1 - u0);
        }

        public float mapV(float modelV) {
            return v0 + (modelV / 16f) * (v1 - v0);
        }
    }

    /** 已登记的纹理：名称 → 首帧图像。 */
    private final Map<String, BufferedImage> images = new HashMap<>();
    /** 半透明标记（含半透明像素或水/熔岩等流体）。 */
    private final Map<String, Boolean> translucent = new HashMap<>();

    private volatile BufferedImage atlasImage;
    private Map<String, UVRect> uvRects = Map.of();

    /** 登记纹理（自动裁剪动画首帧、检测半透明）。重复登记同名纹理会被忽略。 */
    public synchronized void add(String name, BufferedImage image) {
        String key = ResourcePackLoader.normalizePath(name);
        if (key == null || images.containsKey(key)) {
            return;
        }
        BufferedImage firstFrame = cropFirstFrame(image);
        images.put(key, firstFrame);
        translucent.put(key, detectTranslucent(key, firstFrame));
        atlasImage = null; // 标记需要重建
    }

    /** 拼接贴图集（幂等，查询时自动触发，也可显式调用）。 */
    public synchronized void build() {
        if (atlasImage != null) {
            return;
        }
        List<String> names = new ArrayList<>(images.keySet());
        Collections.sort(names); // 固定顺序，保证输出可复现
        int cell = BASE_CELL;
        for (String name : names) {
            BufferedImage img = images.get(name);
            cell = Math.max(cell, roundUp16(Math.max(img.getWidth(), img.getHeight())));
        }
        int stride = cell + PADDING * 2;
        int count = Math.max(1, names.size());
        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil((double) count / cols);

        BufferedImage atlas = new BufferedImage(cols * stride, rows * stride, BufferedImage.TYPE_INT_ARGB);
        Map<String, UVRect> rects = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            BufferedImage img = images.get(name);
            int boxX = (i % cols) * stride;
            int boxY = (i / cols) * stride;
            int x = boxX + PADDING;
            int y = boxY + PADDING;
            paintWithPadding(atlas, img, x, y);
            rects.put(name, new UVRect(
                    (float) x / atlas.getWidth(),
                    (float) y / atlas.getHeight(),
                    (float) (x + img.getWidth()) / atlas.getWidth(),
                    (float) (y + img.getHeight()) / atlas.getHeight()));
        }
        this.uvRects = rects;
        this.atlasImage = atlas;
    }

    /** 输出 PNG 字节。 */
    public byte[] png() {
        build();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(atlasImage, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("贴图集 PNG 编码失败", e);
        }
    }

    /** 查询纹理 uv 矩形；未登记返回 null（调用方回退缺失纹理）。 */
    public UVRect uv(String name) {
        build();
        String key = ResourcePackLoader.normalizePath(name);
        return key == null ? null : uvRects.get(key);
    }

    /** 纹理是否半透明（含半透明像素，或水/熔岩等原版流体）。 */
    public boolean isTranslucent(String name) {
        String key = ResourcePackLoader.normalizePath(name);
        return key != null && translucent.getOrDefault(key, false);
    }

    public int width() {
        build();
        return atlasImage.getWidth();
    }

    public int height() {
        build();
        return atlasImage.getHeight();
    }

    /** 登记纹理数量。 */
    public int size() {
        return images.size();
    }

    /** 原版风格缺失纹理：16×16 品红/黑棋盘。 */
    public static BufferedImage missingTextureImage() {
        BufferedImage img = new BufferedImage(BASE_CELL, BASE_CELL, BufferedImage.TYPE_INT_ARGB);
        int magenta = 0xFFF800F8;
        int black = 0xFF000000;
        for (int y = 0; y < BASE_CELL; y++) {
            for (int x = 0; x < BASE_CELL; x++) {
                boolean m = ((x / 8) + (y / 8)) % 2 == 0;
                img.setRGB(x, y, m ? magenta : black);
            }
        }
        return img;
    }

    // ---------- 内部 ----------

    /** 动画长条取首帧：高为宽整数倍（或反向）时按正方形裁剪第一帧。 */
    private static BufferedImage cropFirstFrame(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (h > w && h % w == 0) {
            return img.getSubimage(0, 0, w, w);
        }
        if (w > h && w % h == 0) {
            return img.getSubimage(0, 0, h, h);
        }
        return img;
    }

    private static boolean detectTranslucent(String name, BufferedImage img) {
        if (name.contains("water")) {
            return true; // 原版水 PNG 本身不透明，半透明由渲染层实现（熔岩不透明，不在此列）
        }
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha > 0 && alpha < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 绘制纹理并把边缘像素复制到 padding 环，防止线性过滤串色。 */
    private static void paintWithPadding(BufferedImage atlas, BufferedImage img, int x, int y) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                atlas.setRGB(x + i, y + j, img.getRGB(i, j));
            }
        }
        // 上下边缘
        for (int i = 0; i < w; i++) {
            atlas.setRGB(x + i, y - 1, img.getRGB(i, 0));
            atlas.setRGB(x + i, y + h, img.getRGB(i, h - 1));
        }
        // 左右边缘
        for (int j = 0; j < h; j++) {
            atlas.setRGB(x - 1, y + j, img.getRGB(0, j));
            atlas.setRGB(x + w, y + j, img.getRGB(w - 1, j));
        }
        // 四角
        atlas.setRGB(x - 1, y - 1, img.getRGB(0, 0));
        atlas.setRGB(x + w, y - 1, img.getRGB(w - 1, 0));
        atlas.setRGB(x - 1, y + h, img.getRGB(0, h - 1));
        atlas.setRGB(x + w, y + h, img.getRGB(w - 1, h - 1));
    }

    private static int roundUp16(int v) {
        return ((v + BASE_CELL - 1) / BASE_CELL) * BASE_CELL;
    }
}
