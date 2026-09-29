package online.yudream.base.plugin.skin.interfaces.http;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 皮肤正面立绘拼装布局测试：用每区域唯一纯色构造新旧两种材质，
 * 断言画布尺寸、四肢位置、头身遮盖顺序与旧版镜像行为。
 */
class YuDreamSkinHttpFacadeRenderTest {

    /** 不透明纯色。 */
    private static final int HEAD = 0xFF_E6194B;
    private static final int HAT = 0xFF_4363FF;
    private static final int BODY = 0xFF_3FDB84;
    private static final int ARM_R = 0xFF_FEB443;
    private static final int ARM_L = 0xFF_42D4F4;
    private static final int LEG_R = 0xFF_F032E6;
    private static final int LEG_L = 0xFF_BFEF45;

    /** 64x64 新版材质：正面区域各填唯一色，外层区域留透明（帽层除外，用于测遮盖顺序）。 */
    private static BufferedImage modernSkin(boolean withHat) {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(skin, 8, 8, 8, 8, HEAD);
        fill(skin, 20, 20, 8, 12, BODY);
        fill(skin, 44, 20, 4, 12, ARM_R);
        fill(skin, 36, 52, 4, 12, ARM_L);
        fill(skin, 4, 20, 4, 12, LEG_R);
        fill(skin, 20, 52, 4, 12, LEG_L);
        if (withHat) {
            fill(skin, 40, 8, 8, 8, HAT);
        }
        return skin;
    }

    /** 64x32 旧版材质：只有右肢与帽层。 */
    private static BufferedImage legacySkin() {
        BufferedImage skin = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        fill(skin, 8, 8, 8, 8, HEAD);
        fill(skin, 20, 20, 8, 12, BODY);
        fill(skin, 44, 20, 4, 12, ARM_R);
        fill(skin, 4, 20, 4, 12, LEG_R);
        return skin;
    }

    private static void fill(BufferedImage image, int x, int y, int w, int h, int rgb) {
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                image.setRGB(x + dx, y + dy, rgb);
            }
        }
    }

    private static int pixelAt(BufferedImage render, int unitX, int unitY, int unit) {
        return render.getRGB(unitX * unit + unit / 2, unitY * unit + unit / 2);
    }

    @Test
    void rendersModernSkinAtConfiguredHeightWithCorrectPartPlacement() {
        BufferedImage render = YuDreamSkinHttpFacade.renderFrontView(modernSkin(false), 320);
        assertEquals(160, render.getWidth());
        assertEquals(320, render.getHeight());
        int unit = 10;
        // 头（无帽层 → 露出头色）
        assertEquals(HEAD, pixelAt(render, 8, 4, unit));
        // 身体
        assertEquals(BODY, pixelAt(render, 8, 14, unit));
        // 右臂在左缘、左臂在右缘
        assertEquals(ARM_R, pixelAt(render, 2, 14, unit));
        assertEquals(ARM_L, pixelAt(render, 14 - 2, 14, unit));
        // 右腿在左、左腿在右
        assertEquals(LEG_R, pixelAt(render, 6, 26, unit));
        assertEquals(LEG_L, pixelAt(render, 10, 26, unit));
    }

    @Test
    void hatOverlayCoversHeadWhenOpaque() {
        BufferedImage render = YuDreamSkinHttpFacade.renderFrontView(modernSkin(true), 320);
        assertEquals(HAT, pixelAt(render, 8, 4, 10));
    }

    @Test
    void legacySkinMirrorsRightLimbRegionsToLeftSlots() {
        BufferedImage render = YuDreamSkinHttpFacade.renderFrontView(legacySkin(), 320);
        assertEquals(160, render.getWidth());
        assertEquals(320, render.getHeight());
        int unit = 10;
        assertEquals(ARM_R, pixelAt(render, 2, 14, unit));
        // 旧版左肢 = 右肢区域镜像
        assertEquals(ARM_R, pixelAt(render, 14 - 2, 14, unit));
        assertEquals(LEG_R, pixelAt(render, 6, 26, unit));
        assertEquals(LEG_R, pixelAt(render, 10, 26, unit));
        assertEquals(HEAD, pixelAt(render, 8, 4, unit));
        assertEquals(BODY, pixelAt(render, 8, 14, unit));
    }

    @Test
    void modernSkinDrawsLimbOverlayAboveBase() {
        BufferedImage skin = modernSkin(false);
        int overlay = 0xFF_00BABA;
        fill(skin, 44, 36, 4, 12, overlay);
        BufferedImage render = YuDreamSkinHttpFacade.renderFrontView(skin, 320);
        assertEquals(overlay, pixelAt(render, 2, 14, 10));
    }

    @Test
    void regionsBeyondSourceAreSkippedSafely() {
        // 16x16 的小图：所有四肢/外层区域越界，应安全跳过不抛错，仅头部可渲染（头部区域 8,8 在界内）
        BufferedImage tiny = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        fill(tiny, 8, 8, 8, 8, HEAD);
        BufferedImage render = YuDreamSkinHttpFacade.renderFrontView(tiny, 320);
        assertEquals(160, render.getWidth());
        assertEquals(HEAD, pixelAt(render, 8, 4, 10));
        // 身体区域越界跳过 → 该处保持透明
        assertEquals(0, pixelAt(render, 8, 14, 10));
        assertTrue(true);
    }

    @Test
    void heightBelowMinimumStillUsesUnitTwo() {
        BufferedImage render = YuDreamSkinHttpFacade.renderFrontView(modernSkin(false), 10);
        // unit 下限 2 → 画布 32x64
        assertEquals(32, render.getWidth());
        assertEquals(64, render.getHeight());
    }

    private interface Unused {}
}
